package net.topikachu.rag.business.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.UploadResult;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

public interface DocumentService {

    Mono<UploadResult> upload(FilePart filePart, String fileName, boolean overwrite, String userId, List<String> tags);

    Mono<BatchUploadResponse> uploadBatch(Flux<FilePart> files, boolean overwrite, String userId, List<String> tags);

    Mono<Page<Document>> listDocuments(int page, int size, String keyword);

    Mono<Void> removeDocumentById(String id);

    Mono<Void> removeDocumentsBatch(List<String> ids);

    Mono<List<String>> getAllTags();

    Mono<Void> retryIngestion(String id, String userId);
}
