package net.topikachu.rag.business.document.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContextService;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.business.document.vo.DownloadedDocument;
import net.topikachu.rag.business.document.vo.DocumentPermissionUpdateRequest;
import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.observability.TracingSupport;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;
    private final TracingSupport tracingSupport;
    private final CurrentUserContextService currentUserContextService;

    @PostMapping("/docs/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> upLoad(
            @RequestPart("file") Mono<FilePart> file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            @RequestParam(value = "tags", required = false) List<String> tags,
            @org.springframework.web.bind.annotation.RequestHeader(value = "X-Locust-Run-Id", required = false) String locustRunId,
            Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> file.flatMap(part -> documentService.upload(
                                part,
                                fileName,
                                overwrite,
                                StringUtils.hasText(userId) ? userId : null,
                                tags))
                        .doOnSubscribe(subscription -> tagCurrentUploadTrace(userId, fileName, overwrite, tags, locustRunId))
                        .map(AjaxResult::success));
    }

    @PostMapping(path = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> uploadBatch(
            @RequestPart("files") Flux<FilePart> files,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(value = "tags", required = false) List<String> tags,
            Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    String effectiveUserId = StringUtils.hasText(userId) ? userId : null;
                    log.info("Batch upload requested by user={}, overwrite={}, tags={}", effectiveUserId, overwrite, tags);
                    return documentService.uploadBatch(files, overwrite, effectiveUserId, tags)
                            .map(AjaxResult::success);
                });
    }

    @GetMapping("/docs")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return documentService.listDocuments(page, size, keyword)
                .map(AjaxResult::success);
    }

    @DeleteMapping("/docs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> remove(@PathVariable String id) {
        return documentService.removeDocumentById(id)
                .thenReturn(AjaxResult.success());
    }

    @GetMapping("/docs/{id}/download")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<Resource>> download(@PathVariable String id) {
        return documentService.downloadDocumentById(id)
                .map(this::toDownloadResponse);
    }

    // 原文预览：通过 docUuid 从 MinIO 拉取文件，返回二进制流供浏览器直接打开
    @GetMapping("/docs/by-uuid/{docUuid}/preview")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<ResponseEntity<Resource>> preview(@PathVariable String docUuid, Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .flatMap(username -> documentService.previewDocumentByDocUuid(
                        docUuid,
                        currentUserContextService.resolveByUsername(username)))  // 校验用户是否有权访问该文档
                .map(this::toPreviewResponse);
    }

    @DeleteMapping("/docs")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> removeBatch(@RequestBody List<String> ids) {
        return documentService.removeDocumentsBatch(ids)
                .thenReturn(AjaxResult.success());
    }

    @GetMapping("/tags")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> getTags(@RequestParam(value = "spaceCodes", required = false) List<String> spaceCodes,
                                    Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .flatMap(username -> documentService.getAccessibleTags(
                        currentUserContextService.resolveByUsername(username),
                        new SearchScope(spaceCodes, List.of())))
                .map(AjaxResult::success);
    }

    @GetMapping("/spaces")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> getSpaces(Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .flatMap(username -> documentService.getAccessibleSpaceCodes(
                        currentUserContextService.resolveByUsername(username)))
                .map(AjaxResult::success);
    }

    @PostMapping("/docs/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> retry(@PathVariable String id, Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    String effectiveUserId = StringUtils.hasText(userId) ? userId : null;
                    log.info("Retry ingestion requested by user={}, docId={}", effectiveUserId, id);
                    return documentService.retryIngestion(id, effectiveUserId)
                            .thenReturn(AjaxResult.success("Retry started", null));
                });
    }

    @PatchMapping("/docs/{id}/permissions")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> updatePermissions(@PathVariable String id,
                                              @RequestBody DocumentPermissionUpdateRequest request) {
        return documentService.updatePermissions(id, request)
                .thenReturn(AjaxResult.success());
    }

    @PostMapping("/docs/backfill-acl-metadata")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> backfillAclMetadata() {
        return documentService.backfillAclMetadata()
                .map(count -> AjaxResult.success("ACL metadata backfill submitted", count));
    }

    private void tagCurrentUploadTrace(String userId,
            String fileName,
            boolean overwrite,
            List<String> tags,
            String locustRunId) {
        Map<String, Object> traceTags = new LinkedHashMap<>();
        traceTags.put("langfuse.user.id", userId);
        traceTags.put("document.file_name", fileName);
        traceTags.put("document.overwrite", overwrite);
        traceTags.put("document.tags", tags == null ? "" : String.join(",", tags));
        traceTags.put("locust.run_id", locustRunId);
        tracingSupport.tagCurrent(traceTags);
    }

    private ResponseEntity<Resource> toDownloadResponse(DownloadedDocument document) {
        ContentDisposition contentDisposition = ContentDisposition.attachment()
                .filename(document.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new InputStreamResource(document.inputStream()));
    }

    private ResponseEntity<Resource> toPreviewResponse(DownloadedDocument document) {
        ContentDisposition contentDisposition = ContentDisposition.inline()
                .filename(document.fileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(detectMediaType(document.fileName()))
                .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition.toString())
                .body(new InputStreamResource(document.inputStream()));
    }

    private MediaType detectMediaType(String fileName) {
        if (fileName == null) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        String normalized = fileName.toLowerCase(java.util.Locale.ROOT);
        if (normalized.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF;
        }
        if (normalized.endsWith(".txt") || normalized.endsWith(".md")) {
            return MediaType.TEXT_PLAIN;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
