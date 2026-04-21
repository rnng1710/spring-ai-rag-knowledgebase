package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.UploadItemResult;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.EtlJobStarter;
import net.topikachu.rag.service.etl.EtlPipeline;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    @Override
    public Mono<UploadResult> upload(FilePart filePart, String fileName, boolean overwrite, String userId, List<String> tags) {
        String requestedFileName = StringUtils.hasText(fileName) ? fileName : filePart.filename();
        return createTempFile()
                .flatMap(tempFile -> filePart.transferTo(tempFile)
                        .then(processUploadedFile(tempFile, requestedFileName, overwrite, userId, tags))
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
                        }),3)
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

                    vectorStore.delete(new FilterExpressionBuilder().eq("doc_uuid", doc.getDocUuid()).build());
                    log.info("Deleted from vector store: docUuid={}", doc.getDocUuid());

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
    public Mono<List<String>> getAllTags() {
        return Mono.fromCallable(() -> {
                    List<Document> docs = documentMapper.selectList(Wrappers.<Document>lambdaQuery()
                            .select(Document::getTags)
                            .isNotNull(Document::getTags));
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

                    Path path = Paths.get(inputDirectory, doc.getDocUuid(), doc.getFileName());
                    if (!Files.exists(path)) {
                        doc.setStatus(DocumentStatus.FAILED.name());
                        doc.setErrorMessage("源文件已丢失，无法重试，请重新上传");
                        doc.setUpdateDate(LocalDateTime.now());
                        documentMapper.updateById(doc);
                        return;
                    }

                    doc.setStatus(DocumentStatus.UPLOADED.name());
                    doc.setErrorMessage(null);
                    doc.setErrorStack(null);
                    doc.setUpdateDate(LocalDateTime.now());
                    documentMapper.updateById(doc);

                    etlJobStarter.start(
                            etlPipeline.ingestionByPath(path, doc.getDocUuid(), userId, doc.getTags()),
                            "Retry ingestion " + path);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
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
            List<String> tags) {
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
                    Path baseDir = Paths.get(inputDirectory).toAbsolutePath().normalize();
                    Path dir = baseDir.resolve(docUuid).normalize();
                    Path target = dir.resolve(finalFileName).normalize();

                    if (!target.startsWith(dir)) {
                        throw new IllegalArgumentException("Illegal file name (path traversal detected).");
                    }

                    try {
                        Files.createDirectories(dir);
                        if (Files.exists(target) && !overwrite) {
                            throw new IllegalStateException("The file already exists overwrite=false: " + target);
                        }

                        moveTempToTarget(tempFile, target, overwrite);

                        Document doc = new Document();
                        doc.setDocUuid(docUuid);
                        doc.setFileName(finalFileName);
                        doc.setStatus(DocumentStatus.UPLOADED.name());
                        doc.setFileHash(hash);
                        doc.setTags(tags);
                        doc.setCreateDate(LocalDateTime.now());
                        doc.setUpdateDate(LocalDateTime.now());

                        int inserted = documentMapper.insert(doc);
                        if (inserted != 1) {
                            safeDelete(target);
                            throw new IllegalStateException("Insert knowledge_document failed。");
                        }

                        etlJobStarter.start(
                                etlPipeline.ingestionByPath(target, docUuid, userId, tags),
                                "Ingestion " + target);

                        log.info("Upload created: docUuid={}, fileName={}, status={}, fileHash={}, path={}",
                                docUuid, finalFileName, doc.getStatus(), hash, target);
                        return toResult(doc, true);
                    } catch (Exception e) {
                        safeDelete(tempFile);
                        safeDelete(target);
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

    private void moveTempToTarget(Path tmp, Path target, boolean overwrite) throws IOException {
        CopyOption[] options = overwrite
                ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE}
                : new CopyOption[]{StandardCopyOption.ATOMIC_MOVE};

        try {
            Files.move(tmp, target, options);
        } catch (AtomicMoveNotSupportedException ex) {
            CopyOption[] fallback = overwrite
                    ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                    : new CopyOption[]{};
            Files.move(tmp, target, fallback);
        }
    }
}
