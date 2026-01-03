package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.service.etl.EtlPipeline;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

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
public class DocumentServiceImpl extends ServiceImpl<DocumentMapper, Document>  implements DocumentService{

    @Autowired
    private DocumentMapper documentMapper;

    @Autowired
    private EtlPipeline etlPipeline;

    @Value("${rag.upload.max-size-bytes:52428800}")
    private long maxSizeBytes;

    @Value("${input.directory}")
    private String inputDirectory;

    @Value("${rag.upload.allowed-ext:pdf,doc,docx,txt,md}")
    private String allowedExt;

    /**
     * upload file method
     * @param file
     * @param fileName
     * @param overwrite
     * @return boolean
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean upload(MultipartFile file, String fileName, boolean overwrite) throws IOException {

// 1. Verification files(empty files/file size/file extension/whitelist)
        validateFile(file, fileName);

        // 2. Generate docUuid
        String docUuid = generateDocUuid();

        // Select the final file name：Use the parameter "fileName" first，otherwise, use the original file name.
        String finalFileName = StringUtils.hasText(fileName) ? fileName : file.getOriginalFilename();
        finalFileName = sanitizeFileName(finalFileName);

        // 3. Calculate the save path（input.directory/<docUuid>/filename）
        Path dir = Paths.get(inputDirectory).toAbsolutePath().normalize().resolve(docUuid);
        Path target = dir.resolve(finalFileName).normalize();

        // Prevent path traversal：target must under the dir.
        if (!target.startsWith(dir)) {
            throw new IllegalArgumentException("Invalid file name (path traversal detected).");
        }

        try {
            // 4. Save the file to the disk.
            Files.createDirectories(dir);

            if (Files.exists(target) && !overwrite) {
                throw new IllegalStateException("File already exists and overwrite=false: " + target);
            }

            // Using streaming replication is more controllable；when using overwrite, use REPLACE_EXISTING instead.
            try (InputStream in = file.getInputStream()) {
                CopyOption[] options = overwrite
                        ? new CopyOption[]{StandardCopyOption.REPLACE_EXISTING}
                        : new CopyOption[]{};
                Files.copy(in, target, options);
            }

            String hash = saveAndSha256(file.getInputStream(), target);

            Document document = this.lambdaQuery()
                    .eq(Document::getFileHash , hash)
                    .one();

            if (document != null) {
                throw new IllegalStateException("Document cannot be duplicated " );
            }


            // 5. Write records in MySQL （UPLOADED）
            Document doc = new Document();
            doc.setDocUuid(docUuid);
            doc.setFileName(finalFileName);
            doc.setStatus(DocumentStatus.UPLOADED.name());
            doc.setFileHash(hash);

            // public field
            doc.setCreateDate(LocalDateTime.now());
            doc.setUpdateDate(LocalDateTime.now());

            int inserted = documentMapper.insert(doc);
            if (inserted != 1) {
                // DB failed. Try to delete the disk files to avoid orphan files.
                safeDelete(target);
                throw new IllegalStateException("Insert knowledge_document failed.");
            }

            // 5.1 Upload triggers a full ingestion scan
            // Note: this will scan the whole input.directory
            etlPipeline.ingestionByPath(target)
                    .subscribe(
                            null,
                            err -> log.error("Ingestion failed: {}", target, err),
                            () -> log.info("Ingestion done: {}", target)
                    );

            // 6. return docUuid + filename + status
            log.info("Document uploaded: docUuid={}, fileName={}, status={}, path={}",
                    docUuid, finalFileName, doc.getStatus(), target);

            return true;

        } catch (Exception e) {
            // Attempt to write a failed state.
            log.error("Upload failed: docUuid={}, fileName={}, overwrite={}, err={}",
                    docUuid, finalFileName, overwrite, e.toString(), e);

            // When failing, try to clear the files (if they have been written);
            safeDelete(target);

            // Roll back the transaction.
            throw e;
        }

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
        if (idx < 0 || idx == filename.length() - 1) return "";
        return filename.substring(idx + 1).toLowerCase(Locale.ROOT);
    }

    private void safeDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (Exception ignore) {
        }
    }


    private String saveAndSha256(InputStream in, Path target) {
        try {
            Files.createDirectories(target.getParent());

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(in, md);
                 OutputStream out = Files.newOutputStream(target)) {

                byte[] buf = new byte[1024 * 1024];
                int n;
                while ((n = dis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            throw new RuntimeException("Failed to save and hash: " + target, e);
        }
    }
}
