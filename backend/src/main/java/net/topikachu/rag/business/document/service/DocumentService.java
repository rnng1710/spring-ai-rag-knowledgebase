package net.topikachu.rag.business.document.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.DocumentPermissionUpdateRequest;
import net.topikachu.rag.business.document.vo.DownloadedDocument;
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

    Mono<DownloadedDocument> downloadDocumentById(String id);

    Mono<List<String>> getAccessibleTags(CurrentUserContext currentUserContext, SearchScope searchScope);

    Mono<List<String>> getAccessibleSpaceCodes(CurrentUserContext currentUserContext);

    Mono<Void> retryIngestion(String id, String userId);

    Mono<Void> updatePermissions(String id, DocumentPermissionUpdateRequest request);

    Mono<Integer> backfillAclMetadata();
}
