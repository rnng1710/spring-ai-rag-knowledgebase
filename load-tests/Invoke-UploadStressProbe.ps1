#requires -Version 7.0

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string[]]$FilePaths,

    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin",
    [string]$Password = "admin",
    [int[]]$ConcurrencyLevels = @(1, 5, 10, 20, 30),
    [int]$HealthProbeIntervalMs = 200,
    [int]$PollIntervalSeconds = 3,
    [int]$StageSettleSeconds = 10,
    [int]$DocPollTimeoutSeconds = 600,
    [int]$ListPageSize = 200,
    [string]$OutputDir = (Join-Path $PSScriptRoot "results"),
    [switch]$SkipHealthProbe,
    [switch]$SkipCleanup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

Import-Module ThreadJob -ErrorAction Stop

function Get-PercentileValue {
    param(
        [double[]]$Values,
        [double]$Percentile
    )

    if (-not $Values -or $Values.Count -eq 0) {
        return $null
    }

    $sorted = $Values | Sort-Object
    $rank = [Math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    if ($rank -lt 0) {
        $rank = 0
    }
    if ($rank -ge $sorted.Count) {
        $rank = $sorted.Count - 1
    }
    return [Math]::Round([double]$sorted[$rank], 2)
}

function Get-LatencySummary {
    param([object[]]$Samples)

    $durations = @($Samples | ForEach-Object { [double]$_.latency_ms })
    if ($durations.Count -eq 0) {
        return [ordered]@{
            count = 0
            min_ms = $null
            max_ms = $null
            avg_ms = $null
            p50_ms = $null
            p95_ms = $null
            p99_ms = $null
        }
    }

    $measure = $durations | Measure-Object -Minimum -Maximum -Average
    return [ordered]@{
        count = $durations.Count
        min_ms = [Math]::Round([double]$measure.Minimum, 2)
        max_ms = [Math]::Round([double]$measure.Maximum, 2)
        avg_ms = [Math]::Round([double]$measure.Average, 2)
        p50_ms = Get-PercentileValue -Values $durations -Percentile 50
        p95_ms = Get-PercentileValue -Values $durations -Percentile 95
        p99_ms = Get-PercentileValue -Values $durations -Percentile 99
    }
}

function Get-FileSha256 {
    param([string]$Path)

    $hash = Get-FileHash -Algorithm SHA256 -Path $Path
    return $hash.Hash.ToLowerInvariant()
}

function Get-StagePrefix {
    param(
        [int]$Concurrency,
        [int]$StageIndex
    )

    $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
    return "lt-$stamp-c$Concurrency-s$StageIndex"
}

function Get-LoginToken {
    param(
        [string]$BaseUrl,
        [string]$Username,
        [string]$Password
    )

    $response = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" `
        -Method Post `
        -ContentType "application/json" `
        -Body (@{ username = $Username; password = $Password } | ConvertTo-Json)

    if ($response.code -ne 0 -or -not $response.data.access_token) {
        throw "Login failed: $($response.msg)"
    }

    return $response.data.access_token
}

function Get-AuthHeaders {
    param([string]$Token)

    return @{
        Authorization = "Bearer $Token"
    }
}

function Get-AllDocumentsByKeyword {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [string]$Keyword,
        [int]$PageSize
    )

    $page = 1
    $records = @()
    while ($true) {
        $encodedKeyword = [System.Uri]::EscapeDataString($Keyword)
        $url = "$BaseUrl/api/v1/docs?page=$page&size=$PageSize&keyword=$encodedKeyword"
        $response = Invoke-RestMethod -Uri $url -Method Get -Headers (Get-AuthHeaders -Token $Token)
        if ($response.code -ne 0) {
            throw "List documents failed: $($response.msg)"
        }

        $pageData = $response.data
        if ($null -eq $pageData) {
            break
        }

        $pageRecords = @($pageData.records)
        $records += $pageRecords

        $total = [int]($pageData.total | ForEach-Object { $_ })
        if ($records.Count -ge $total -or $pageRecords.Count -eq 0) {
            break
        }

        $page++
    }

    return ,$records
}

function Get-StatusDistribution {
    param([object[]]$Documents)

    $distribution = [ordered]@{}
    foreach ($doc in $Documents) {
        $status = if ($doc.status) { $doc.status } else { "UNKNOWN" }
        if (-not $distribution.Contains($status)) {
            $distribution[$status] = 0
        }
        $distribution[$status]++
    }
    return $distribution
}

function Wait-ForStageTerminalState {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [string]$Keyword,
        [int]$ExpectedCount,
        [int]$PageSize,
        [int]$TimeoutSeconds,
        [int]$PollIntervalSeconds,
        [int]$StageSettleSeconds
    )

    Start-Sleep -Seconds $StageSettleSeconds

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $documents = Get-AllDocumentsByKeyword -BaseUrl $BaseUrl -Token $Token -Keyword $Keyword -PageSize $PageSize
        $allTerminal = $documents.Count -ge $ExpectedCount
        if ($allTerminal) {
            foreach ($doc in $documents) {
                if ($doc.status -notin @("COMPLETED", "FAILED")) {
                    $allTerminal = $false
                    break
                }
            }
        }

        if ($allTerminal) {
            return ,$documents
        }

        Start-Sleep -Seconds $PollIntervalSeconds
    } while ((Get-Date) -lt $deadline)

    return ,(Get-AllDocumentsByKeyword -BaseUrl $BaseUrl -Token $Token -Keyword $Keyword -PageSize $PageSize)
}

function Remove-Documents {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [object[]]$Documents
    )

    foreach ($doc in $Documents) {
        if (-not $doc.id) {
            continue
        }
        try {
            Invoke-RestMethod -Uri "$BaseUrl/api/v1/docs/$($doc.id)" `
                -Method Delete `
                -Headers (Get-AuthHeaders -Token $Token) | Out-Null
        } catch {
            Write-Warning "Failed to delete document id=$($doc.id): $($_.Exception.Message)"
        }
    }
}

function Get-PrometheusSnapshot {
    param([string]$BaseUrl)

    try {
        $raw = Invoke-WebRequest -Uri "$BaseUrl/actuator/prometheus" -Method Get -UseBasicParsing
    } catch {
        return [ordered]@{
            available = $false
            error = $_.Exception.Message
        }
    }

    $content = $raw.Content
    $lines = $content -split "`n"

    $heapUsedBytes = 0.0
    $gcPauseCount = 0.0
    foreach ($line in $lines) {
        if ($line -match '^jvm_memory_used_bytes\{.*area="heap".*\}\s+([0-9Ee\+\-\.]+)$') {
            $heapUsedBytes += [double]$matches[1]
        } elseif ($line -match '^jvm_gc_pause_seconds_count\{.*\}\s+([0-9Ee\+\-\.]+)$') {
            $gcPauseCount += [double]$matches[1]
        }
    }

    $snapshot = [ordered]@{
        available = $true
        process_cpu_usage = $null
        system_cpu_usage = $null
        jvm_threads_live_threads = $null
        jvm_threads_daemon_threads = $null
        jvm_heap_used_bytes = [Math]::Round($heapUsedBytes, 2)
        jvm_gc_pause_count = [Math]::Round($gcPauseCount, 2)
    }

    foreach ($metricName in @(
            "process_cpu_usage",
            "system_cpu_usage",
            "jvm_threads_live_threads",
            "jvm_threads_daemon_threads")) {
        $match = $lines | Where-Object { $_ -match "^$metricName(?:\{.*\})?\s+([0-9Ee\+\-\.]+)$" } | Select-Object -First 1
        if ($match) {
            $null = $match -match "^$metricName(?:\{.*\})?\s+([0-9Ee\+\-\.]+)$"
            $snapshot[$metricName] = [Math]::Round([double]$matches[1], 6)
        }
    }

    return $snapshot
}

function Start-HealthProbeJob {
    param(
        [string]$BaseUrl,
        [int]$IntervalMs,
        [string]$StopFile
    )

    return Start-ThreadJob -Name "health-probe" -ScriptBlock {
        param($BaseUrl, $IntervalMs, $StopFile)

        $samples = New-Object System.Collections.Generic.List[object]
        while (-not (Test-Path -LiteralPath $StopFile)) {
            $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
            $statusCode = 0
            $ok = $false
            $errorMessage = $null

            try {
                $response = Invoke-WebRequest -Uri "$BaseUrl/actuator/health" -Method Get -UseBasicParsing -TimeoutSec 10
                $statusCode = [int]$response.StatusCode
                $ok = $statusCode -ge 200 -and $statusCode -lt 300
            } catch {
                if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
                    $statusCode = [int]$_.Exception.Response.StatusCode.value__
                }
                $errorMessage = $_.Exception.Message
            } finally {
                $stopwatch.Stop()
            }

            $samples.Add([pscustomobject]@{
                    timestamp = (Get-Date).ToString("o")
                    latency_ms = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
                    status_code = $statusCode
                    success = $ok
                    error = $errorMessage
                }) | Out-Null

            Start-Sleep -Milliseconds $IntervalMs
        }

        return $samples.ToArray()
    } -ArgumentList $BaseUrl, $IntervalMs, $StopFile
}

function Invoke-UploadStage {
    param(
        [string]$BaseUrl,
        [string]$Token,
        [string[]]$Files,
        [int]$Concurrency,
        [int]$StageIndex,
        [string]$OutputDir,
        [bool]$SkipHealthProbe,
        [int]$HealthProbeIntervalMs,
        [int]$StageSettleSeconds,
        [int]$DocPollTimeoutSeconds,
        [int]$PollIntervalSeconds,
        [int]$ListPageSize,
        [bool]$SkipCleanup
    )

    $stagePrefix = Get-StagePrefix -Concurrency $Concurrency -StageIndex $StageIndex
    $stageDir = Join-Path $OutputDir $stagePrefix
    New-Item -ItemType Directory -Path $stageDir -Force | Out-Null

    Write-Host "Running stage $stagePrefix with concurrency=$Concurrency and files=$($Files.Count)"

    $prometheusBefore = Get-PrometheusSnapshot -BaseUrl $BaseUrl

    $healthJob = $null
    $stopFile = Join-Path $stageDir "health.stop"
    if (-not $SkipHealthProbe) {
        $healthJob = Start-HealthProbeJob -BaseUrl $BaseUrl -IntervalMs $HealthProbeIntervalMs -StopFile $stopFile
    }

    $uploadResults = $Files | ForEach-Object -Parallel {
        $filePath = $_
        $uploadName = "{0}-{1}-{2}" -f $using:stagePrefix, [guid]::NewGuid().ToString("N").Substring(0, 8), [System.IO.Path]::GetFileName($filePath)
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $statusCode = 0
        $bodyText = $null
        $parsed = $null

        $handler = [System.Net.Http.HttpClientHandler]::new()
        $client = [System.Net.Http.HttpClient]::new($handler)
        $client.Timeout = [TimeSpan]::FromMinutes(15)
        $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new("Bearer", $using:Token)

        $multipart = [System.Net.Http.MultipartFormDataContent]::new()
        $fileBytes = [System.IO.File]::ReadAllBytes($filePath)
        $fileContent = [System.Net.Http.ByteArrayContent]::new($fileBytes)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/octet-stream")
        $multipart.Add($fileContent, "file", [System.IO.Path]::GetFileName($filePath))
        $multipart.Add([System.Net.Http.StringContent]::new($uploadName), "fileName")
        $multipart.Add([System.Net.Http.StringContent]::new("false"), "overwrite")

        try {
            $response = $client.PostAsync("$using:BaseUrl/api/v1/docs/upload", $multipart).GetAwaiter().GetResult()
            $statusCode = [int]$response.StatusCode
            $bodyText = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
            try {
                $parsed = $bodyText | ConvertFrom-Json -ErrorAction Stop
            } catch {
                $parsed = $null
            }
        } catch {
            $bodyText = $_.Exception.Message
        } finally {
            $stopwatch.Stop()
            $multipart.Dispose()
            $fileContent.Dispose()
            $client.Dispose()
            $handler.Dispose()
        }

        [pscustomobject]@{
            source_path = $filePath
            upload_name = $uploadName
            latency_ms = [Math]::Round($stopwatch.Elapsed.TotalMilliseconds, 2)
            status_code = $statusCode
            success = [bool]($parsed -and $parsed.code -eq 0 -and $parsed.success -eq $true)
            created = [bool]($parsed -and $parsed.data -and $parsed.data.created -eq $true)
            doc_uuid = if ($parsed -and $parsed.data) { $parsed.data.docUuid } else { $null }
            file_name = if ($parsed -and $parsed.data) { $parsed.data.fileName } else { $null }
            response_msg = if ($parsed) { $parsed.msg } else { $bodyText }
            response_body = $bodyText
        }
    } -ThrottleLimit $Concurrency

    if ($healthJob) {
        New-Item -ItemType File -Path $stopFile -Force | Out-Null
        Wait-Job $healthJob | Out-Null
        $receivedHealthSamples = @(Receive-Job $healthJob)
        if ($receivedHealthSamples.Count -eq 1 -and $receivedHealthSamples[0] -is [System.Array]) {
            $healthSamples = @($receivedHealthSamples[0])
        } else {
            $healthSamples = $receivedHealthSamples
        }
        Remove-Job $healthJob
    } else {
        $healthSamples = @()
    }

    $expectedCreatedCount = @($uploadResults | Where-Object { $_.success -and $_.created }).Count
    $documents = @()
    if ($expectedCreatedCount -gt 0) {
        $documents = Wait-ForStageTerminalState `
            -BaseUrl $BaseUrl `
            -Token $Token `
            -Keyword $stagePrefix `
            -ExpectedCount $expectedCreatedCount `
            -PageSize $ListPageSize `
            -TimeoutSeconds $DocPollTimeoutSeconds `
            -PollIntervalSeconds $PollIntervalSeconds `
            -StageSettleSeconds $StageSettleSeconds
    }

    $prometheusAfter = Get-PrometheusSnapshot -BaseUrl $BaseUrl

    $uploadStatusCounts = [ordered]@{
        success = @($uploadResults | Where-Object { $_.success }).Count
        created = @($uploadResults | Where-Object { $_.success -and $_.created }).Count
        existed = @($uploadResults | Where-Object { $_.success -and -not $_.created }).Count
        failed_http_4xx = @($uploadResults | Where-Object { $_.status_code -ge 400 -and $_.status_code -lt 500 }).Count
        failed_http_5xx = @($uploadResults | Where-Object { $_.status_code -ge 500 }).Count
        failed_transport = @($uploadResults | Where-Object { $_.status_code -eq 0 -and -not $_.success }).Count
    }

    $healthSummary = Get-LatencySummary -Samples $healthSamples
    $healthSummary["http_failures"] = @($healthSamples | Where-Object { -not $_.success }).Count
    $healthSummary["http_5xx"] = @($healthSamples | Where-Object { $_.status_code -ge 500 }).Count

    $stageSummary = [ordered]@{
        stage_prefix = $stagePrefix
        requested_concurrency = $Concurrency
        effective_parallel_uploads = [Math]::Min($Concurrency, $Files.Count)
        source_file_count = $Files.Count
        upload_counts = $uploadStatusCounts
        upload_latency = Get-LatencySummary -Samples $uploadResults
        health_probe = $healthSummary
        document_status_distribution = Get-StatusDistribution -Documents $documents
        completed_docs = @($documents | Where-Object { $_.status -eq "COMPLETED" }).Count
        failed_docs = @($documents | Where-Object { $_.status -eq "FAILED" }).Count
        prometheus_before = $prometheusBefore
        prometheus_after = $prometheusAfter
        upload_results_file = (Join-Path $stageDir "upload-results.json")
        health_results_file = (Join-Path $stageDir "health-results.json")
        documents_file = (Join-Path $stageDir "documents.json")
    }

    $uploadResults | ConvertTo-Json -Depth 8 | Set-Content -Path $stageSummary["upload_results_file"]
    $healthSamples | ConvertTo-Json -Depth 6 | Set-Content -Path $stageSummary["health_results_file"]
    $documents | ConvertTo-Json -Depth 8 | Set-Content -Path $stageSummary["documents_file"]

    if (-not $SkipCleanup -and $documents.Count -gt 0) {
        Remove-Documents -BaseUrl $BaseUrl -Token $Token -Documents $documents
        $stageSummary["cleanup_attempted"] = $true
    } else {
        $stageSummary["cleanup_attempted"] = $false
    }

    return $stageSummary
}

$resolvedFiles = @()
foreach ($path in $FilePaths) {
    $resolved = Resolve-Path -Path $path -ErrorAction Stop
    $resolvedFiles += $resolved.Path
}

$resolvedFiles = $resolvedFiles | Sort-Object -Unique
if ($resolvedFiles.Count -eq 0) {
    throw "No input files were resolved."
}

$hashMap = @{}
foreach ($file in $resolvedFiles) {
    $hash = Get-FileSha256 -Path $file
    if ($hashMap.ContainsKey($hash)) {
        Write-Warning "Duplicate file hash detected. Reusing the same PDF will not stress ETL: $file and $($hashMap[$hash])"
    } else {
        $hashMap[$hash] = $file
    }
}

$maxConcurrency = ($ConcurrencyLevels | Measure-Object -Maximum).Maximum
if ($resolvedFiles.Count -lt $maxConcurrency) {
    Write-Warning "Source file count ($($resolvedFiles.Count)) is smaller than max concurrency ($maxConcurrency). Actual upload concurrency will cap at the number of files."
}

New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

$token = Get-LoginToken -BaseUrl $BaseUrl -Username $Username -Password $Password

$overall = [ordered]@{
    started_at = (Get-Date).ToString("o")
    base_url = $BaseUrl
    file_count = $resolvedFiles.Count
    unique_hash_count = $hashMap.Count
    concurrency_levels = $ConcurrencyLevels
    output_dir = $OutputDir
    stages = @()
}

$stageIndex = 0
foreach ($concurrency in $ConcurrencyLevels) {
    $stageIndex++
    $stageSummary = Invoke-UploadStage `
        -BaseUrl $BaseUrl `
        -Token $token `
        -Files $resolvedFiles `
        -Concurrency $concurrency `
        -StageIndex $stageIndex `
        -OutputDir $OutputDir `
        -SkipHealthProbe:$SkipHealthProbe `
        -HealthProbeIntervalMs $HealthProbeIntervalMs `
        -StageSettleSeconds $StageSettleSeconds `
        -DocPollTimeoutSeconds $DocPollTimeoutSeconds `
        -PollIntervalSeconds $PollIntervalSeconds `
        -ListPageSize $ListPageSize `
        -SkipCleanup:$SkipCleanup

    $overall.stages += $stageSummary
}

$overall["finished_at"] = (Get-Date).ToString("o")
$overall["summary_file"] = (Join-Path $OutputDir "summary.json")

$overall | ConvertTo-Json -Depth 10 | Set-Content -Path $overall["summary_file"]

Write-Host "Stress probe finished. Summary written to $($overall["summary_file"])"
Write-Host ($overall | ConvertTo-Json -Depth 6)
