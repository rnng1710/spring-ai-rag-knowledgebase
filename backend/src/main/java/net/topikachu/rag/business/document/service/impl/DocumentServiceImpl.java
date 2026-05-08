package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.entity.KnowledgeAclRefreshTask;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.KnowledgeAclRefreshTaskMapper;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.DocumentPermissionUpdateRequest;
import net.topikachu.rag.business.document.vo.DownloadedDocument;
import net.topikachu.rag.business.document.vo.UploadItemResult;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.DocumentChunkMetadataBuilder;
import net.topikachu.rag.service.etl.EtlJobStarter;
import net.topikachu.rag.service.etl.EtlPipeline;
import net.topikachu.rag.service.etl.MilvusChunkRow;
import net.topikachu.rag.service.etl.MilvusWriteGateway;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final EtlPipeline etlPipeline;
    private final VectorStore vectorStore;
    private final EtlJobStarter etlJobStarter;
    private final TracingSupport tracingSupport;
    private final MilvusWriteGateway milvusWriteGateway;
    private final DocumentChunkMetadataBuilder metadataBuilder;
    private final KnowledgeAclRefreshTaskMapper aclRefreshTaskMapper;
    private final EtlJobService etlJobService;
    private final Gson gson = new Gson();
    private final DocumentUploadTransactionService documentUploadTransactionService;
    private final ObjectStorageService objectStorageService;

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    @Value("${rag.acl-refresh.retry.max-retries:5}")
    private int aclRefreshMaxRetries;

    @Value("${rag.acl-refresh.retry.batch-size:20}")
    private int aclRefreshBatchSize;

    @Override
    public Mono<UploadResult> upload(FilePart filePart, String fileName, boolean overwrite, String userId, List<String> tags) {
        String requestedFileName = StringUtils.hasText(fileName) ? fileName : filePart.filename();
        String contentType = filePart.headers().getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : filePart.headers().getContentType().toString();
        return createTempFile()
                .flatMap(tempFile -> filePart.transferTo(tempFile)
                        .then(processUploadedFile(tempFile, requestedFileName, overwrite, userId, tags, contentType))
                        .onErrorResume(error -> cleanupOnError(tempFile, null).then(Mono.error(error))));
    }

    @Override
    public Mono<BatchUploadResponse> uploadBatch(Flux<FilePart> files, boolean overwrite, String userId, List<String> tags) {
        return files.flatMapSequential(file -> upload(file, null, overwrite, userId, tags)
                        .map(result -> UploadItemResult.builder()
                                .success(true)
                                .created(result.isCreated())
                                .docUuid(result.getDocUuid())
                                .fileName(result.getFileName())
                                .status(result.getStatus())
                                .fileHash(result.getFileHash())
                                .build())
                        .onErrorResume(error -> {
                            log.error("Batch upload failed: fileName={}, err={}", file.filename(), error.toString(), error);
                            return Mono.just(UploadItemResult.builder()
                                    .success(false)
                                    .created(false)
                                    .fileName(file.filename())
                                    .error(error.getMessage())
                                    .build());
                        }), 3)
                .collectList()
                .map(results -> {
                    int success = 0;
                    int created = 0;
                    int existed = 0;
                    int failed = 0;
                    for (UploadItemResult result : results) {
                        if (result.isSuccess()) {
                            success++;
                            if (result.isCreated()) {
                                created++;
                            } else {
                                existed++;
                            }
                        } else {
                            failed++;
                        }
                    }
                    return BatchUploadResponse.builder()
                            .total(results.size())
                            .successCount(success)
                            .createdCount(created)
                            .existedCount(existed)
                            .failedCount(failed)
                            .results(results)
                            .build();
                });
    }

    @Override
    public Mono<Page<Document>> listDocuments(int page, int size, String keyword) {
        return Mono.fromCallable(() -> {
                    Page<Document> resultPage = new Page<>(page, size);
                    LambdaQueryWrapper<Document> query = Wrappers.lambdaQuery();
                    if (StringUtils.hasText(keyword)) {
                        query.like(Document::getFileName, keyword);
                    }
                    query.orderByDesc(Document::getCreateDate);
                    return documentMapper.selectPage(resultPage, query);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> removeDocumentById(String id) {
        return Mono.fromRunnable(() -> {
                    Document doc = documentMapper.selectById(id);
                    if (doc == null) {
                        return;
                    }

                    milvusWriteGateway.deleteByDocUuid(doc.getDocUuid()).block();
                    log.info("Deleted from Milvus: docUuid={}", doc.getDocUuid());

                    if (StringUtils.hasText(doc.getObjectKey())) {
                        objectStorageService.deleteObject(doc.getObjectKey());
                        log.info("Deleted object storage file: objectKey={}", doc.getObjectKey());
                    } else {
                        Path baseDir = Paths.get(inputDirectory).toAbsolutePath().normalize();
                        Path dir = baseDir.resolve(doc.getDocUuid()).normalize();
                        if (Files.exists(dir)) {
                            try (var walk = Files.walk(dir)) {
                                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                                    try {
                                        Files.delete(path);
                                    } catch (IOException ignore) {
                                    }
                                });
                            } catch (IOException e) {
                                throw new RuntimeException("Failed to delete file directory " + dir, e);
                            }
                            log.info("Deleted file directory: {}", dir);
                        }
                    }

                    aclRefreshTaskMapper.delete(Wrappers.<KnowledgeAclRefreshTask>lambdaQuery()
                            .eq(KnowledgeAclRefreshTask::getDocUuid, doc.getDocUuid()));
                    documentMapper.deleteById(id);
                    log.info("Deleted from database: id={}", id);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Void> removeDocumentsBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Mono.empty();
        }
        return Flux.fromIterable(ids)
                .concatMap(this::removeDocumentById)
                .then();
    }

    @Override
    public Mono<DownloadedDocument> downloadDocumentById(String id) {
        return Mono.fromCallable(() -> {
                    Document doc = documentMapper.selectById(id);
                    if (doc == null) {
                        throw new IllegalArgumentException("Document not found: " + id);
                    }
                    if (!StringUtils.hasText(doc.getObjectKey())) {
                        throw new IllegalStateException("Source file objectKey is missing");
                    }
                    return new DownloadedDocument(doc.getFileName(), objectStorageService.getObject(doc.getObjectKey()));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<String>> getAccessibleTags(CurrentUserContext currentUserContext, SearchScope searchScope) {
        return Mono.fromCallable(() -> {
                    QueryWrapper<Document> query = buildAccessibleDocumentQuery(currentUserContext, searchScope);
                    query.select("tags")
                            .isNotNull("tags");
                    List<Document> docs = documentMapper.selectList(query);
                    if (docs == null || docs.isEmpty()) {
                        return Collections.<String>emptyList();
                    }
                    return docs.stream()
                            .map(Document::getTags)
                            .filter(Objects::nonNull)
                            .flatMap(List::stream)
                            .filter(StringUtils::hasText)
                            .map(String::trim)
                            .distinct()
                            .sorted()
                            .collect(Collectors.toList());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<String>> getAccessibleSpaceCodes(CurrentUserContext currentUserContext) {
        return Mono.fromCallable(() -> {
                    QueryWrapper<Document> query = buildAccessibleDocumentQuery(currentUserContext, SearchScope.empty());
                    query.select("space_code")
                            .isNotNull("space_code")
                            .groupBy("space_code")
                            .orderByAsc("space_code");
                    return documentMapper.selectList(query).stream()
                            .map(Document::getSpaceCode)
                            .filter(StringUtils::hasText)
                            .distinct()
                            .toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> retryIngestion(String id, String userId) {
        return Mono.fromRunnable(() -> {
                    Document doc = documentMapper.selectById(id);
                    if (doc == null) {
                        throw new IllegalArgumentException("Document not found: " + id);
                    }
                    if (!DocumentStatus.FAILED.name().equals(doc.getStatus())) {
                        throw new IllegalStateException("Only FAILED documents can be retried");
                    }
                    if (doc.getRetryCount() != null && doc.getRetryCount() >= 3) {
                        throw new IllegalStateException("Max retry (3) exceeded, please contact support");
                    }

                    if (!StringUtils.hasText(doc.getObjectKey()) || !objectStorageService.exists(doc.getObjectKey())) {
                        doc.setStatus(DocumentStatus.FAILED.name());
                        doc.setErrorMessage("源文件已丢失，无法重试，请重新上传");
                        doc.setUpdateDate(LocalDateTime.now());
                        documentMapper.updateById(doc);
                        return;
                    }

                    doc.setStatus(DocumentStatus.UPLOADED.name());
                    doc.setErrorMessage(null);
                    doc.setErrorStack(null);
                    doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
                    doc.setAclRefreshError(null);
                    doc.setAclRefreshTime(null);
                    doc.setUpdateDate(LocalDateTime.now());
                    documentMapper.updateById(doc);

                    etlJobService.retryDocumentIngestion(doc, doc.getObjectKey(), userId);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Void> updatePermissions(String id, DocumentPermissionUpdateRequest request) {
        return Mono.fromRunnable(() -> {
                    Document doc = documentMapper.selectById(id);
                    if (doc == null) {
                        throw new IllegalArgumentException("Document not found: " + id);
                    }
                    applyPermissionUpdate(doc, request);
                    doc.setAclVersion(nextAclVersion(doc));
                    doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
                    doc.setAclRefreshError(null);
                    doc.setAclRefreshTime(null);
                    doc.setUpdateDate(LocalDateTime.now());
                    documentMapper.updateById(doc);
                    enqueueAclRefreshTask(doc);
                    boolean refreshed = processSingleAclRefreshTask(doc.getDocUuid(), doc.getAclVersion());
                    if (!refreshed) {
                        log.warn("ACL metadata refresh queued for retry: docUuid={}, aclVersion={}",
                                doc.getDocUuid(), doc.getAclVersion());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Integer> backfillAclMetadata() {
        return Mono.fromCallable(() -> {
                    List<Document> docs = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                            .isNotNull(Document::getDocUuid)
                            .isNotNull(Document::getFileName));
                    int refreshed = 0;
                    for (Document doc : docs) {
                        prepareBackfillRefresh(doc);
                        enqueueAclRefreshTask(doc);
                        if (processSingleAclRefreshTask(doc.getDocUuid(), doc.getAclVersion())) {
                            refreshed++;
                        }
                    }
                    return refreshed;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    public int processPendingAclRefreshTasks() {
        LocalDateTime now = LocalDateTime.now();
        List<KnowledgeAclRefreshTask> tasks = aclRefreshTaskMapper.selectList(Wrappers.<KnowledgeAclRefreshTask>lambdaQuery()
                .in(KnowledgeAclRefreshTask::getStatus, List.of(AclRefreshStatus.PENDING.name(), AclRefreshStatus.FAILED.name()))
                .and(wrapper -> wrapper.isNull(KnowledgeAclRefreshTask::getNextRetryTime)
                        .or()
                        .le(KnowledgeAclRefreshTask::getNextRetryTime, now))
                .orderByAsc(KnowledgeAclRefreshTask::getNextRetryTime)
                .last("LIMIT " + aclRefreshBatchSize));

        int processed = 0;
        for (KnowledgeAclRefreshTask task : tasks) {
            if (task.getRetryCount() != null && task.getRetryCount() >= aclRefreshMaxRetries) {
                continue;
            }
            processSingleAclRefreshTask(task.getDocUuid(), task.getTargetAclVersion());
            processed++;
        }
        return processed;
    }

    private Mono<Path> createTempFile() {
        return Mono.fromCallable(() -> {
                    Path baseDir = Paths.get(inputDirectory).toAbsolutePath().normalize();
                    Files.createDirectories(baseDir);
                    return Files.createTempFile(baseDir, "upload_", ".tmp");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<UploadResult> processUploadedFile(Path tempFile,
                                                   String fileName,
                                                   boolean overwrite,
                                                   String userId,
                                                   List<String> tags,
                                                   String contentType) {
        return tracingSupport.traceMono("etl.upload_accept",
                uploadTraceTags(fileName, null, userId, tags),
                Mono.fromCallable(() -> {
                    validateFile(tempFile, fileName);

                    String finalFileName = sanitizeFileName(fileName);
                    String hash = sha256(tempFile);
                    Document existed = findByHash(hash);
                    if (existed != null) {
                        safeDelete(tempFile);
                        if (overwrite) {
                            throw new IllegalArgumentException("The file already exists : " + existed.getFileName());
                        }
                        return toResult(existed, false);
                    }

                    String docUuid = generateDocUuid();
                    String objectKey = "documents/" + docUuid + "/" + finalFileName;
                    boolean objectUploaded = false;
                    try {
                        objectStorageService.putObject(objectKey, tempFile, contentType);
                        objectUploaded = true;

                        Document doc = new Document();
                        doc.setDocUuid(docUuid);
                        doc.setFileName(finalFileName);
                        doc.setStatus(DocumentStatus.UPLOADED.name());
                        doc.setFileHash(hash);
                        doc.setTags(tags);
                        doc.setSpaceCode("public");
                        doc.setIsPublic(Boolean.TRUE);
                        doc.setOwnerDeptId(null);
                        doc.setAllowedRoles(null);
                        doc.setAllowedDeptIds(null);
                        doc.setAclVersion(1);
                        doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
                        doc.setAclRefreshError(null);
                        doc.setAclRefreshTime(null);
                        doc.setCreateDate(LocalDateTime.now());
                        doc.setUpdateDate(LocalDateTime.now());
                        doc.setObjectKey(objectKey);

                        documentUploadTransactionService.createDocumentAndQueueJob(doc, objectKey, userId);

                        log.info("Upload created: docUuid={}, fileName={}, status={}, fileHash={}, path={}",
                                docUuid, finalFileName, doc.getStatus(), hash, objectKey);
                        safeDelete(tempFile);
                        return toResult(doc, true);
                    } catch (Exception e) {
                        safeDelete(tempFile);
                        if (objectUploaded) {
                            objectStorageService.deleteObject(objectKey);
                        }
                        throw e;
                    }
                })
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Map<String, Object> uploadTraceTags(String fileName, String docUuid, String userId, List<String> tags) {
        Map<String, Object> traceTags = new LinkedHashMap<>();
        traceTags.put("document.file_name", fileName);
        traceTags.put("document.doc_uuid", docUuid);
        traceTags.put("document.user_id", userId);
        traceTags.put("document.tags", tags == null ? "" : String.join(",", tags));
        return traceTags;
    }

    private Mono<UploadResult> cleanupOnError(Path tempFile, Path target) {
        return Mono.fromRunnable(() -> {
                    safeDelete(tempFile);
                    safeDelete(target);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.empty());
    }

    private UploadResult toResult(Document doc, boolean created) {
        return UploadResult.builder()
                .created(created)
                .docUuid(doc.getDocUuid())
                .fileName(doc.getFileName())
                .status(doc.getStatus())
                .fileHash(doc.getFileHash())
                .build();
    }

    private Document findByHash(String hash) {
        if (!StringUtils.hasText(hash)) {
            return null;
        }
        return documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getFileHash, hash)
                .last("LIMIT 1"));
    }

    private void validateFile(Path path, String fileName) throws IOException {
        if (path == null || !Files.exists(path)) {
            throw new IllegalArgumentException("File is empty.");
        }
        if (Files.size(path) > maxSizeBytes) {
            throw new IllegalArgumentException("File too large. max=" + maxSizeBytes + " bytes");
        }

        String name = StringUtils.hasText(fileName) ? fileName : path.getFileName().toString();
        name = name == null ? "" : name.trim();
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("File name is blank.");
        }

        String ext = getExtension(name);
        Set<String> allow = Arrays.stream(allowedExt.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        if (ext.isEmpty() || !allow.contains(ext)) {
            throw new IllegalArgumentException("File extension not allowed: " + ext + ", allowed=" + allow);
        }
    }

    private String generateDocUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sanitizeFileName(String name) {
        String normalized = name.trim();
        normalized = Paths.get(normalized).getFileName().toString();
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (!StringUtils.hasText(normalized)) {
            throw new IllegalArgumentException("Invalid file name after sanitize.");
        }
        return normalized;
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1) {
            return "";
        }
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignore) {
        }
    }

    private String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path);
                 DigestInputStream dis = new DigestInputStream(in, digest)) {
                dis.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hash for " + path, e);
        }
    }

    private QueryWrapper<Document> buildAccessibleDocumentQuery(CurrentUserContext currentUserContext, SearchScope searchScope) {
        QueryWrapper<Document> query = new QueryWrapper<>();
        applyAccessControl(query, currentUserContext);
        applySpaceScope(query, searchScope);
        applyTagScope(query, searchScope == null ? List.of() : searchScope.requestedTags());
        return query;
    }

    private void applyAccessControl(QueryWrapper<Document> query, CurrentUserContext currentUserContext) {
        if (currentUserContext == null || currentUserContext.isAdmin()) {
            return;
        }
        query.and(wrapper -> {
            wrapper.eq("is_public", 1);
            if (StringUtils.hasText(currentUserContext.role())) {
                wrapper.or().apply("JSON_CONTAINS(allowed_roles, JSON_QUOTE({0}))", currentUserContext.role());
            }
            if (StringUtils.hasText(currentUserContext.deptId())) {
                wrapper.or().apply("JSON_CONTAINS(allowed_dept_ids, JSON_QUOTE({0}))", currentUserContext.deptId());
                wrapper.or().eq("owner_dept_id", currentUserContext.deptId());
            }
        });
    }

    private void applySpaceScope(QueryWrapper<Document> query, SearchScope searchScope) {
        if (searchScope == null || searchScope.requestedSpaceCodes().isEmpty()) {
            return;
        }
        query.and(wrapper -> {
            boolean first = true;
            for (String spaceCode : searchScope.requestedSpaceCodes()) {
                if (first) {
                    wrapper.eq("space_code", spaceCode);
                    first = false;
                } else {
                    wrapper.or().eq("space_code", spaceCode);
                }
            }
        });
    }

    private void applyTagScope(QueryWrapper<Document> query, List<String> requestedTags) {
        List<String> normalizedTags = normalizeStringList(requestedTags);
        if (normalizedTags.isEmpty()) {
            return;
        }
        query.and(wrapper -> {
            boolean first = true;
            for (String tag : normalizedTags) {
                if (first) {
                    wrapper.apply("JSON_CONTAINS(tags, JSON_QUOTE({0}))", tag);
                    first = false;
                } else {
                    wrapper.or().apply("JSON_CONTAINS(tags, JSON_QUOTE({0}))", tag);
                }
            }
        });
    }

    private List<String> normalizeStringList(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private List<String> normalizePermissionList(List<String> values) {
        List<String> normalized = normalizeStringList(values);
        return normalized.isEmpty() ? null : List.copyOf(new LinkedHashSet<>(normalized));
    }

    private void applyPermissionUpdate(Document doc, DocumentPermissionUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Permission payload is required");
        }
        doc.setSpaceCode(StringUtils.hasText(request.getSpaceCode()) ? request.getSpaceCode().trim() : "public");
        doc.setOwnerDeptId(StringUtils.hasText(request.getOwnerDeptId()) ? request.getOwnerDeptId().trim() : null);
        doc.setAllowedRoles(normalizePermissionList(request.getAllowedRoles()));
        doc.setAllowedDeptIds(normalizePermissionList(request.getAllowedDeptIds()));
        doc.setIsPublic(Boolean.TRUE.equals(request.getIsPublic()));
    }

    private void prepareBackfillRefresh(Document doc) {
        if (doc.getAclVersion() == null || doc.getAclVersion() < 1) {
            doc.setAclVersion(1);
        }
        doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
        doc.setAclRefreshError(null);
        doc.setAclRefreshTime(null);
        doc.setUpdateDate(LocalDateTime.now());
        documentMapper.updateById(doc);
    }

    private int nextAclVersion(Document doc) {
        return doc.getAclVersion() == null ? 1 : doc.getAclVersion() + 1;
    }

    private void enqueueAclRefreshTask(Document doc) {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeAclRefreshTask existing = findAclRefreshTask(doc.getDocUuid(), doc.getAclVersion());
        if (existing == null) {
            KnowledgeAclRefreshTask task = new KnowledgeAclRefreshTask();
            task.setId(UUID.randomUUID().toString().replace("-", ""));
            task.setDocUuid(doc.getDocUuid());
            task.setTargetAclVersion(doc.getAclVersion());
            task.setStatus(AclRefreshStatus.PENDING.name());
            task.setRetryCount(0);
            task.setNextRetryTime(now);
            task.setCreateDate(now);
            task.setUpdateDate(now);
            aclRefreshTaskMapper.insert(task);
            return;
        }
        existing.setStatus(AclRefreshStatus.PENDING.name());
        existing.setRetryCount(0);
        existing.setLastError(null);
        existing.setNextRetryTime(now);
        existing.setUpdateDate(now);
        aclRefreshTaskMapper.updateById(existing);
    }

    private boolean processSingleAclRefreshTask(String docUuid, Integer targetAclVersion) {
        KnowledgeAclRefreshTask task = findAclRefreshTask(docUuid, targetAclVersion);
        if (task == null) {
            return false;
        }

        Document doc = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                .eq(Document::getDocUuid, docUuid)
                .last("LIMIT 1"));
        if (doc == null) {
            markTaskFailed(task, "Document not found");
            return false;
        }
        if (!Objects.equals(doc.getAclVersion(), targetAclVersion)) {
            aclRefreshTaskMapper.deleteById(task.getId());
            log.info("Dropped stale ACL refresh task: docUuid={}, targetVersion={}, currentVersion={}",
                    docUuid, targetAclVersion, doc.getAclVersion());
            return false;
        }

        markTaskRunning(task);
        markDocumentAclRefreshStatus(doc, AclRefreshStatus.RUNNING, null, null);
        try {
            refreshVectorMetadata(doc);
            markTaskSuccess(task);
            markDocumentAclRefreshStatus(doc, AclRefreshStatus.SUCCESS, null, LocalDateTime.now());
            return true;
        } catch (Exception error) {
            String summary = summarizeError(error);
            markTaskFailed(task, summary);
            markDocumentAclRefreshStatus(doc, AclRefreshStatus.FAILED, summary, null);
            log.warn("ACL metadata refresh failed: docUuid={}, aclVersion={}, error={}",
                    docUuid, targetAclVersion, summary, error);
            return false;
        }
    }

    private void refreshVectorMetadata(Document doc) {
        List<MilvusChunkRow> chunks = milvusWriteGateway.queryChunksByDocUuid(doc.getDocUuid()).block();
        if (chunks == null || chunks.isEmpty()) {
            throw new IllegalStateException("No Milvus chunks found for doc_uuid=" + doc.getDocUuid());
        }

        List<JsonObject> rows = chunks.stream()
                .map(chunk -> toAclRefreshRow(doc, chunk))
                .toList();

        milvusWriteGateway.upsert(rows).block();
    }

    private JsonObject toAclRefreshRow(Document doc, MilvusChunkRow chunk) {
        Map<String, Object> metadata = metadataBuilder.build(
                doc,
                doc.getDocUuid(),
                doc.getFileName(),
                doc.getTags(),
                chunk.metadata(),
                doc.getAclVersion());
        JsonObject row = new JsonObject();
        row.addProperty("doc_id", chunk.docId());
        row.addProperty("content", chunk.content());
        row.add("metadata", gson.toJsonTree(metadata));
        row.add("embedding", gson.toJsonTree(chunk.embedding()));
        row.add("sparse_vector", gson.toJsonTree(chunk.sparseVector()));
        return row;
    }

    private KnowledgeAclRefreshTask findAclRefreshTask(String docUuid, Integer targetAclVersion) {
        return aclRefreshTaskMapper.selectOne(Wrappers.<KnowledgeAclRefreshTask>lambdaQuery()
                .eq(KnowledgeAclRefreshTask::getDocUuid, docUuid)
                .eq(KnowledgeAclRefreshTask::getTargetAclVersion, targetAclVersion)
                .last("LIMIT 1"));
    }

    private void markTaskRunning(KnowledgeAclRefreshTask task) {
        task.setStatus(AclRefreshStatus.RUNNING.name());
        task.setUpdateDate(LocalDateTime.now());
        aclRefreshTaskMapper.updateById(task);
    }

    private void markTaskSuccess(KnowledgeAclRefreshTask task) {
        task.setStatus(AclRefreshStatus.SUCCESS.name());
        task.setLastError(null);
        task.setNextRetryTime(null);
        task.setUpdateDate(LocalDateTime.now());
        aclRefreshTaskMapper.updateById(task);
    }

    private void markTaskFailed(KnowledgeAclRefreshTask task, String errorMessage) {
        int nextRetryCount = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
        task.setStatus(AclRefreshStatus.FAILED.name());
        task.setRetryCount(nextRetryCount);
        task.setLastError(errorMessage);
        task.setNextRetryTime(calculateNextRetryTime(nextRetryCount));
        task.setUpdateDate(LocalDateTime.now());
        aclRefreshTaskMapper.updateById(task);
    }

    private void markDocumentAclRefreshStatus(Document doc,
                                              AclRefreshStatus status,
                                              String errorMessage,
                                              LocalDateTime refreshTime) {
        documentMapper.update(null, Wrappers.<Document>lambdaUpdate()
                .set(Document::getAclRefreshStatus, status.name())
                .set(Document::getAclRefreshError, errorMessage)
                .set(Document::getAclRefreshTime, refreshTime)
                .set(Document::getUpdateDate, LocalDateTime.now())
                .eq(Document::getId, doc.getId()));
        doc.setAclRefreshStatus(status.name());
        doc.setAclRefreshError(errorMessage);
        doc.setAclRefreshTime(refreshTime);
    }

    private LocalDateTime calculateNextRetryTime(int retryCount) {
        return LocalDateTime.now().plus(switch (retryCount) {
            case 1 -> 5;
            case 2 -> 15;
            case 3 -> 60;
            case 4 -> 360;
            default -> 1440;
        }, ChronoUnit.MINUTES);
    }

    private String summarizeError(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (!StringUtils.hasText(message)) {
            message = root.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
