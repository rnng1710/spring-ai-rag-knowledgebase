package net.topikachu.rag.service.storage;

import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Path;

public interface ObjectStorageService {
    Mono<Void> putObject(String objectKey, Path source, String contentType);

    Mono<Path> downloadToTempFile(String objectKey, String fileName);

    Mono<InputStream> getObject(String objectKey);

    Mono<Void> deleteObject(String objectKey);

    Mono<Boolean> exists(String objectKey);
}
