package net.topikachu.rag.service.storage;

import io.minio.*;
import io.minio.errors.ErrorResponseException;
import lombok.RequiredArgsConstructor;
import net.topikachu.rag.config.ObjectStorageProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class MinioObjectStorageService implements ObjectStorageService {

    private final ObjectStorageProperties properties;
    private final MinioClient minioClient;

    @Override
    public Mono<Void> putObject(String objectKey, Path source, String contentType) {
        return Mono.fromCallable(() -> {
                    validateObjectKey(objectKey);
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
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Path> downloadToTempFile(String objectKey, String fileName) {
        return Mono.fromCallable(() -> {
                    validateObjectKey(objectKey);
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
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteObject(String objectKey) {
        return Mono.fromCallable(() -> {
                    if (!StringUtils.hasText(objectKey)) {
                        return null;
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
                            return null;
                        }
                        throw new RuntimeException("Failed to delete object: " + objectKey, e);
                    }
                    return null;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Boolean> exists(String objectKey) {
        return Mono.fromCallable(() -> {
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
                    }
                })
                .subscribeOn(Schedulers.boundedElastic());
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
        if (!StringUtils.hasText(objectKey)) {
            throw new IllegalArgumentException("ObjectKey is required");
        }
        if (objectKey.startsWith("/") || objectKey.contains("\\") || objectKey.contains("//")) {
            throw new IllegalArgumentException("Illegal objectKey: " + objectKey);
        }
        for (String part : objectKey.split("/")) {
            if (!StringUtils.hasText(part) || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Illegal objectKey: " + objectKey);
            }
        }
    }

    @Override
    public Mono<InputStream> getObject(String objectKey) {
        return Mono.fromCallable(() -> {
                    validateObjectKey(objectKey);
                    InputStream stream = minioClient.getObject(
                            GetObjectArgs.builder()
                                    .bucket(properties.getBucket())
                                    .object(objectKey)
                                    .build()
                    );
                    return stream;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String sanitizeFileName(String fileName) {
        String name = StringUtils.hasText(fileName) ? fileName.trim() : "source";
        name = Paths.get(name).getFileName().toString();
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        return StringUtils.hasText(name) ? name : "source";
    }
}
