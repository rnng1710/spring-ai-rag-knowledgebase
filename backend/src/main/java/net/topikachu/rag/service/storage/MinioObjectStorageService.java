package net.topikachu.rag.service.storage;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import net.topikachu.rag.config.ObjectStorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class MinioObjectStorageService implements ObjectStorageService{

    private final ObjectStorageProperties properties;
    private final MinioClient minioClient;
    @Override
    public void putObject(String objectKey, Path source, String contentType) {
        validateObjectKey(objectKey);
        try {
            ensureBucketExists();
            String actualContentType = StringUtils.hasText(contentType)
                    ? contentType : "application/octet-stream";
            minioClient.uploadObject(
                    UploadObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .filename(source.toAbsolutePath().normalize().toString())
                            .contentType(actualContentType)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to upload object: " + objectKey, e);
        }
    }

    @Override
    public Path downloadToTempFile(String objectKey, String fileName) {
        validateObjectKey(objectKey);
        try {
            Files.createDirectories(Paths.get(properties.getTempDirectory()));

            String safeFileName = sanitizeFileName(fileName);
            Path tempFile = Files.createTempFile(
                    Paths.get(properties.getTempDirectory()),
                    "etl_",
                    "_" + safeFileName
            );
            try (InputStream in = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build()
            )) {
                Files.copy(in, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            return tempFile;
        } catch (Exception e) {
            throw new RuntimeException("Failed to download object: " + objectKey, e);
        }
    }

    @Override
    public void deleteObject(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return;
        }

        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build()
            );
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())
                    || "NoSuchObject".equals(e.errorResponse().code())) {
                return;
            }
            throw new RuntimeException("Failed to delete object: " + objectKey, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to delete object: " + objectKey, e);
        }
    }

    @Override
    public boolean exists(String objectKey) {
        if (!StringUtils.hasText(objectKey)) {
            return false;
        }
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())
                    || "NoSuchObject".equals(e.errorResponse().code())) {
                return false;
            }
            throw new RuntimeException("Failed to check object: " + objectKey, e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to check object: " + objectKey, e);
        }
    }

    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(
                BucketExistsArgs.builder()
                        .bucket(properties.getBucket())
                        .build()
        );

        if (!exists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder()
                            .bucket(properties.getBucket())
                            .build()
            );
        }
    }

    private void validateObjectKey(String objectKey) {
        if(!StringUtils.hasText(objectKey)) {
            throw new IllegalArgumentException("ObjectKey is required");
        }
        if(objectKey.startsWith("/") || objectKey.contains("\\") || objectKey.contains("//")){
            throw new IllegalArgumentException("Illegal objectKey: " + objectKey);
        }
        for (String part : objectKey.split("/")) {
            if (!StringUtils.hasText(part) || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Illegal objectKey: " + objectKey);
            }
        }
    }

    @Override
    public InputStream getObject(String objectKey) {
        validateObjectKey(objectKey);
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(properties.getBucket())
                            .object(objectKey)
                            .build()
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to open object: " + objectKey, e);
        }
    }

    private String sanitizeFileName(String fileName) {
        String name = StringUtils.hasText(fileName) ? fileName.trim() : "source";
        name = Paths.get(name).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return StringUtils.hasText(name) ? name : "source";
    }
}
