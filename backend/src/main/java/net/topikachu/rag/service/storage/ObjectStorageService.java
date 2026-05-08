package net.topikachu.rag.service.storage;

import java.nio.file.Path;
import java.io.InputStream;

public interface ObjectStorageService {
    void putObject(String objectKey, Path source, String contentType);

    Path downloadToTempFile(String objectKey, String fileName);

    InputStream getObject(String objectKey);

    void deleteObject(String objectKey);

    boolean exists(String objectKey);
}
