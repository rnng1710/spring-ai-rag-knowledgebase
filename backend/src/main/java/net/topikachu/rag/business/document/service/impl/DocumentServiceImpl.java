package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.UploadItemResult;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.service.etl.EtlPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document> implements DocumentService {

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private EtlPipeline etlPipeline;

    @Autowired
    private VectorStore vectorStore;

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    /**
     * upload file method
     * 
     * @param file
     * @param fileName
     * @param overwrite
     * @return boolean
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public UploadResult upload(MultipartFile file, String fileName, boolean overwrite) throws IOException {

        // 1) Verify
        validateFile(file, fileName);

        // 2) Final file name (priority parameter: fileName)
        String finalFileName = StringUtils.hasText(fileName) ? fileName : file.getOriginalFilename();
        finalFileName = sanitizeFileName(finalFileName);

        // 3) Write temporary files while calculating hash (read the stream only once,
        // write it only once)
        Path baseDir = Paths.get(inputDirectory).toAbsolutePath().normalize();
        Files.createDirectories(baseDir);

        Path tmp = Files.createTempFile(baseDir, "upload_", ".tmp");
        String hash;
        try (InputStream in = file.getInputStream()) {
            hash = writeTempAndSha256(in, tmp);
        } catch (Exception e) {
            safeDelete(tmp);
            throw e;
        }

        // 4) Before writing the final file / the library, perform a hash check to
        // ensure there are no duplicates.
        Document existed = findByHash(hash);
        if (existed != null) {
            safeDelete(tmp);

            if (overwrite) {
                throw new IllegalArgumentException("The file already exists : " + existed.getFileName());
            }

            // Idempotent return: Returns existing record information without triggering
            // ingestion
            return toResult(existed, false);
        }

        // 5) New scene: Generate docUuid
        String docUuid = generateDocUuid();

        // 6) Calculate the final storage path input.directory/<docUuid>/filename
        Path dir = baseDir.resolve(docUuid).normalize();
        Path target = dir.resolve(finalFileName).normalize();

        // Prevent directory traversal
        if (!target.startsWith(dir)) {
            safeDelete(tmp);
            throw new IllegalArgumentException("Illegal file name (path traversal detected).");
        }

        try {
            Files.createDirectories(dir);

            if (Files.exists(target) && !overwrite) {
                safeDelete(tmp);
                throw new IllegalStateException("The file already exists overwrite=false: " + target);
            }

            // 7) temp -> target (Priority atomic movement)
            moveTempToTarget(tmp, target, overwrite);

            // 8) Insert into the database（UPLOADED）
            Document doc = new Document();
            doc.setDocUuid(docUuid);
            doc.setFileName(finalFileName);
            doc.setStatus(DocumentStatus.UPLOADED.name());
            doc.setFileHash(hash);
            doc.setCreateDate(LocalDateTime.now());
            doc.setUpdateDate(LocalDateTime.now());

            try {
                int inserted = documentMapper.insert(doc);
                if (inserted != 1) {
                    safeDelete(target);
                    throw new IllegalStateException("Insert knowledge_document failed。");
                }
            } catch (DuplicateKeyException dup) {
                // Concurrently: Another request has already been inserted into the same hash.
                safeDelete(target);

                Document win = findByHash(hash);
                if (win != null) {
                    // Returns the winner's record with no triggering of ingestion
                    return toResult(win, false);
                }
                throw dup;
            }

            // 9) Only when "insertion is successful" will ingestion be triggered ingestion
            etlPipeline.ingestionByPath(target, docUuid)
                    .subscribe(
                            null,
                            err -> log.error("Ingestion failed: {}", target, err),
                            () -> log.info("Ingestion done: {}", target));

            log.info("Upload created: docUuid={}, fileName={}, status={}, fileHash={}, path={}",
                    docUuid, finalFileName, doc.getStatus(), hash, target);

            return toResult(doc, true);

        } catch (Exception e) {
            // Error Cleanup
            safeDelete(tmp);
            safeDelete(target);
            throw e;
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocumentById(Long id) {
        Document doc = this.getById(id);
        if (doc == null)
            return;

        // 1. Remove from Vector Store (Cascade)
        try {
            // Delete by metadata: doc_uuid == doc.getDocUuid()
            vectorStore.delete(new FilterExpressionBuilder().eq("doc_uuid", doc.getDocUuid()).build());
            log.info("Deleted from vector store: docUuid={}", doc.getDocUuid());
        } catch (Exception e) {
            log.warn("Failed to delete from vector store (might not exist or not supported): {}", doc.getDocUuid(), e);
        }

        // 2. Remove file from disk
        try {
            Path baseDir = Paths.get(inputDirectory).toAbsolutePath().normalize();
            Path dir = baseDir.resolve(doc.getDocUuid()).normalize();
            if (Files.exists(dir)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException ignore) {
                                }
                            });
                }
                log.info("Deleted file directory: {}", dir);
            }
        } catch (Exception e) {
            log.warn("Failed to delete file directory", e);
        }

        // 3. Remove from DB
        this.removeById(id);
        log.info("Deleted from database: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeDocumentsBatch(List<Long> ids) {
        if (ids == null || ids.isEmpty())
            return;
        for (Long id : ids) {
            removeDocumentById(id);
        }
    }

    @Override
    public BatchUploadResponse uploadBatch(List<MultipartFile> files, boolean overwrite) {
        if (files == null || files.isEmpty()) {
            return BatchUploadResponse.builder()
                    .total(0).successCount(0).createdCount(0).existedCount(0).failedCount(0)
                    .results(List.of())
                    .build();
        }

        List<UploadItemResult> results = new ArrayList<>(files.size());
        int success = 0, created = 0, existed = 0, failed = 0;

        for (MultipartFile f : files) {
            try {
                UploadResult r = upload(f, null, overwrite);

                results.add(UploadItemResult.builder()
                        .success(true)
                        .created(r.isCreated())
                        .docUuid(r.getDocUuid())
                        .fileName(r.getFileName())
                        .status(r.getStatus())
                        .fileHash(r.getFileHash())
                        .build());

                success++;
                if (r.isCreated())
                    created++;
                else
                    existed++;

            } catch (Exception e) {
                log.error("Batch upload failed: fileName={}, size={}, err={}",
                        safeName(f), (f == null ? -1 : f.getSize()), e.toString(), e);

                results.add(UploadItemResult.builder()
                        .success(false)
                        .created(false)
                        .fileName(safeName(f))
                        .error(e.getMessage())
                        .build());

                failed++;
            }
        }

        return BatchUploadResponse.builder()
                .total(files.size())
                .successCount(success)
                .createdCount(created)
                .existedCount(existed)
                .failedCount(failed)
                .results(results)
                .build();
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
        if (!StringUtils.hasText(hash))
            return null;
        return this.lambdaQuery()
                .eq(Document::getFileHash, hash)
                .last("LIMIT 1")
                .one();
    }

    private void validateFile(MultipartFile file, String fileName) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is empty.");
        }
        if (file.getSize() > maxSizeBytes) {
            throw new IllegalArgumentException("File too large. max=" + maxSizeBytes + " bytes");
        }

        String name = StringUtils.hasText(fileName) ? fileName : file.getOriginalFilename();
        name = (name == null) ? "" : name.trim();
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
        // Required doc_uuid be unique：UUID without hyphens.
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String sanitizeFileName(String name) {
        String n = name.trim();

        // Just keep the file name itself, removing any path components.
        n = Paths.get(n).getFileName().toString();

        // Replace dangerous characters
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");

        if (!StringUtils.hasText(n)) {
            throw new IllegalArgumentException("Invalid file name after sanitize.");
        }
        return n;
    }

    private String getExtension(String filename) {
        int idx = filename.lastIndexOf('.');
        if (idx < 0 || idx == filename.length() - 1)
            return "";
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private void safeDelete(Path p) {
        if (p == null)
            return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
        }
    }

    private String safeName(MultipartFile f) {
        return (f == null) ? null : f.getOriginalFilename();
    }

    private String writeTempAndSha256(InputStream in, Path tmp) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(in, md);
                    OutputStream out = Files.newOutputStream(tmp, StandardOpenOption.TRUNCATE_EXISTING)) {

                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = dis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to write temporary files and calculate hash: " + tmp, e);
        }
    }

    private void moveTempToTarget(Path tmp, Path target, boolean overwrite) throws IOException {
        CopyOption[] options = overwrite
                ? new CopyOption[] { StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE }
                : new CopyOption[] { StandardCopyOption.ATOMIC_MOVE };

        try {
            Files.move(tmp, target, options);
        } catch (AtomicMoveNotSupportedException ex) {
            CopyOption[] fallback = overwrite
                    ? new CopyOption[] { StandardCopyOption.REPLACE_EXISTING }
                    : new CopyOption[] {};
            Files.move(tmp, target, fallback);
        }
    }
}
