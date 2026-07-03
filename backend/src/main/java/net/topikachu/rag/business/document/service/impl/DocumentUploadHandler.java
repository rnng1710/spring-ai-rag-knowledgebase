package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.UploadItemResult;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@Slf4j
@RequiredArgsConstructor
public class DocumentUploadHandler {

    private final DocumentMapper documentMapper;
    private final TracingSupport tracingSupport;
    private final EtlJobService etlJobService;
    private final PlatformTransactionManager transactionManager;
    private final ObjectStorageService objectStorageService;

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    public Mono<UploadResult> upload(FilePart filePart, String fileName, boolean overwrite, String userId, List<String> tags) {
        String requestedFileName = StringUtils.hasText(fileName) ? fileName : filePart.filename();
        String contentType = filePart.headers().getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                : filePart.headers().getContentType().toString();
        return createTempFile()
                .flatMap(tempFile -> filePart.transferTo(tempFile)
                        .then(processUploadedFile(tempFile, requestedFileName, overwrite, userId, tags, contentType))
                        .doFinally(s -> safeDelete(tempFile)));
    }

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
                validateAndSanitize(tempFile, fileName)
                        .flatMap(finalFileName -> computeHash(tempFile)
                                .flatMap(hash -> persistOrDedupe(tempFile, finalFileName, hash, overwrite, userId, tags, contentType))));
    }

    private Mono<UploadResult> persistOrDedupe(Path tempFile, String finalFileName, String hash,
                                               boolean overwrite, String userId, List<String> tags,
                                               String contentType) {
        return lookupExistingDocument(hash)
                .flatMap(existing -> handleExistingDuplicate(existing, overwrite))
                .switchIfEmpty(Mono.defer(() ->
                        createNewDocument(tempFile, finalFileName, hash, userId, tags, contentType)));
    }

    private Mono<String> validateAndSanitize(Path tempFile, String fileName) {
        return Mono.fromCallable(() -> {
                    validateFile(tempFile, fileName);
                    return sanitizeFileName(fileName);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<String> computeHash(Path tempFile) {
        return Mono.fromCallable(() -> sha256(tempFile))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Document> lookupExistingDocument(String hash) {
        return Mono.fromCallable(() -> findByHash(hash))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<UploadResult> handleExistingDuplicate(Document existing, boolean overwrite) {
        if (overwrite) {
            return Mono.error(new IllegalArgumentException(
                    "The file already exists : " + existing.getFileName()));
        }
        return Mono.just(toResult(existing, false));
    }

    private Mono<UploadResult> createNewDocument(Path tempFile, String finalFileName, String hash,
                                                 String userId, List<String> tags, String contentType) {
        String docUuid = generateDocUuid();
        String objectKey = "documents/" + docUuid + "/" + finalFileName;
        Document doc = buildNewDocument(docUuid, finalFileName, hash, tags, objectKey);
        return uploadToStorageAndPersist(tempFile, objectKey, contentType, doc, userId)
                .thenReturn(toResult(doc, true));
    }

    private Document buildNewDocument(String docUuid, String fileName, String hash,
                                      List<String> tags, String objectKey) {
        Document doc = new Document();
        doc.setDocUuid(docUuid);
        doc.setFileName(fileName);
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
        return doc;
    }

    private Mono<Void> uploadToStorageAndPersist(Path tempFile, String objectKey, String contentType,
                                                 Document doc, String userId) {
        AtomicBoolean objectUploaded = new AtomicBoolean(false);
        return objectStorageService.putObject(objectKey, tempFile, contentType)
                .doOnSuccess(v -> objectUploaded.set(true))
                .then(Mono.defer(() -> persistDocumentAndQueueEtl(doc, objectKey, userId)))
                .doOnSuccess(v -> log.info("Upload created: docUuid={}, fileName={}, status={}, fileHash={}, path={}",
                        doc.getDocUuid(), doc.getFileName(), doc.getStatus(), doc.getFileHash(), objectKey))
                .onErrorResume(e -> {
                    Mono<Void> cleanup = objectUploaded.get()
                            ? objectStorageService.deleteObject(objectKey)
                            : Mono.empty();
                    return cleanup.then(Mono.error(e));
                });
    }

    private Mono<Void> persistDocumentAndQueueEtl(Document doc, String objectKey, String userId) {
        return Mono.fromCallable(() -> {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.executeWithoutResult(status -> {
                int inserted = documentMapper.insert(doc);
                if (inserted != 1) {
                    throw new IllegalStateException("Insert document failed");
                }
                etlJobService.queueDocumentIngestionSync(doc, objectKey, userId);
            });
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private Map<String, Object> uploadTraceTags(String fileName, String docUuid, String userId, List<String> tags) {
        Map<String, Object> traceTags = new LinkedHashMap<>();
        traceTags.put("document.file_name", fileName);
        traceTags.put("document.doc_uuid", docUuid);
        traceTags.put("document.user_id", userId);
        traceTags.put("document.tags", tags == null ? "" : String.join(",", tags));
        return traceTags;
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
}
