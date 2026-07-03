package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.entity.KnowledgeAclRefreshTask;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.KnowledgeAclRefreshTaskMapper;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.DocumentPermissionUpdateRequest;
import net.topikachu.rag.business.document.vo.DownloadedDocument;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import net.topikachu.rag.service.etl.MilvusWriteGateway;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final MilvusWriteGateway milvusWriteGateway;
    private final KnowledgeAclRefreshTaskMapper aclRefreshTaskMapper;
    private final EtlJobService etlJobService;
    private final KnowledgeParentBlockService parentBlockService;
    private final ObjectStorageService objectStorageService;
    private final DocumentUploadHandler documentUploadHandler;
    private final DocumentPermissionManager documentPermissionManager;
    private final AclRefreshManager aclRefreshManager;

    @Value("${input.directory}")
    private String inputDirectory;

    @Override
    public Mono<UploadResult> upload(FilePart filePart, String fileName, boolean overwrite, String userId, List<String> tags) {
        return documentUploadHandler.upload(filePart, fileName, overwrite, userId, tags);
    }

    @Override
    public Mono<BatchUploadResponse> uploadBatch(Flux<FilePart> files, boolean overwrite, String userId, List<String> tags) {
        return documentUploadHandler.uploadBatch(files, overwrite, userId, tags);
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
        return Mono.fromCallable(() -> documentMapper.selectById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(doc -> {
                    if (doc == null) {
                        return Mono.empty();
                    }
                    return milvusWriteGateway.deleteByDocUuid(doc.getDocUuid())
                            .doOnSuccess(v -> log.info("Deleted from Milvus: docUuid={}", doc.getDocUuid()))
                            .then(parentBlockService.deleteByDocUuid(doc.getDocUuid()))
                            .then(deleteSourceFile(doc))
                            .then(Mono.fromRunnable(() -> {
                                        aclRefreshTaskMapper.delete(Wrappers.<KnowledgeAclRefreshTask>lambdaQuery()
                                                .eq(KnowledgeAclRefreshTask::getDocUuid, doc.getDocUuid()));
                                        documentMapper.deleteById(id);
                                        log.info("Deleted from database: id={}", id);
                                    }).subscribeOn(Schedulers.boundedElastic()));
                })
                .then();
    }

    private Mono<Void> deleteSourceFile(Document doc) {
        if (StringUtils.hasText(doc.getObjectKey())) {
            return objectStorageService.deleteObject(doc.getObjectKey())
                    .doOnSuccess(v -> log.info("Deleted object storage file: objectKey={}", doc.getObjectKey()));
        }
        return Mono.fromRunnable(() -> {
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
    public Mono<DownloadedDocument> downloadDocumentById(String id) {
        return Mono.fromCallable(() -> {
                    Document doc = documentMapper.selectById(id);
                    if (doc == null) {
                        throw new IllegalArgumentException("Document not found: " + id);
                    }
                    if (!StringUtils.hasText(doc.getObjectKey())) {
                        throw new IllegalStateException("Source file objectKey is missing");
                    }
                    return doc;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(doc -> Mono.usingWhen(
                        objectStorageService.getObject(doc.getObjectKey()),
                        is -> Mono.just(new DownloadedDocument(doc.getFileName(), is)),
                        is -> Mono.empty(),
                        (is, err) -> closeQuietly(is),
                        this::closeQuietly
                ));
    }

    @Override
    public Mono<DownloadedDocument> previewDocumentByDocUuid(String docUuid, CurrentUserContext currentUserContext) {
        return Mono.fromCallable(() -> {
                    Document doc = documentMapper.selectOne(Wrappers.<Document>lambdaQuery()
                            .eq(Document::getDocUuid, docUuid));
                    if (doc == null) {
                        throw new IllegalArgumentException("Document not found: " + docUuid);
                    }
                    if (!documentPermissionManager.canAccessDocument(currentUserContext, doc)) {
                        throw new org.springframework.security.access.AccessDeniedException("Document preview denied");
                    }
                    if (!StringUtils.hasText(doc.getObjectKey())) {
                        throw new IllegalStateException("Source file objectKey is missing");
                    }
                    return doc;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(doc -> Mono.usingWhen(
                        objectStorageService.getObject(doc.getObjectKey()),
                        is -> Mono.just(new DownloadedDocument(doc.getFileName(), is)),
                        is -> Mono.empty(),
                        (is, err) -> closeQuietly(is),
                        this::closeQuietly
                ));
    }

    @Override
    public Mono<List<String>> getAccessibleTags(CurrentUserContext currentUserContext, SearchScope searchScope) {
        return Mono.fromCallable(() -> {
                    QueryWrapper<Document> query = documentPermissionManager.buildAccessibleDocumentQuery(currentUserContext, searchScope);
                    query.select("tags")
                            .isNotNull("tags");
                    List<Document> docs = documentMapper.selectList(query);
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
    public Mono<List<String>> getAccessibleSpaceCodes(CurrentUserContext currentUserContext) {
        return Mono.fromCallable(() -> {
                    QueryWrapper<Document> query = documentPermissionManager.buildAccessibleDocumentQuery(currentUserContext, SearchScope.empty());
                    query.select("space_code")
                            .isNotNull("space_code")
                            .groupBy("space_code")
                            .orderByAsc("space_code");
                    return documentMapper.selectList(query).stream()
                            .map(Document::getSpaceCode)
                            .filter(StringUtils::hasText)
                            .distinct()
                            .toList();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<SearchScope> resolveEffectiveSearchScope(CurrentUserContext currentUserContext, SearchScope requestedScope) {
        SearchScope safeRequestedScope = requestedScope == null ? SearchScope.empty() : requestedScope;
        List<String> requestedSpaces = safeRequestedScope.requestedSpaceCodes();
        if (!requestedSpaces.isEmpty()) {
            return validateRequestedSpaces(currentUserContext, safeRequestedScope);
        }
        String defaultSpace = currentUserContext == null ? null : currentUserContext.defaultSpaceCode();
        if (!StringUtils.hasText(defaultSpace)) {
            return Mono.error(new IllegalStateException("请先配置默认知识空间。"));
        }
        SearchScope defaultScope = new SearchScope(List.of(defaultSpace), safeRequestedScope.requestedTags());
        return validateRequestedSpaces(currentUserContext, defaultScope);
    }

    private Mono<SearchScope> validateRequestedSpaces(CurrentUserContext currentUserContext, SearchScope searchScope) {
        return getAccessibleSpaceCodes(currentUserContext)
                .map(accessibleSpaces -> {
                    Set<String> accessible = new LinkedHashSet<>(accessibleSpaces);
                    for (String requestedSpace : searchScope.requestedSpaceCodes()) {
                        if (!accessible.contains(requestedSpace)) {
                            throw new IllegalStateException("知识空间不可访问，请重新选择或配置默认知识空间。");
                        }
                    }
                    return searchScope;
                });
    }

    @Override
    public Mono<Void> retryIngestion(String id, String userId) {
        return Mono.fromCallable(() -> documentMapper.selectById(id))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(doc -> {
                    if (doc == null) {
                        return Mono.error(new IllegalArgumentException("Document not found: " + id));
                    }
                    if (!DocumentStatus.FAILED.name().equals(doc.getStatus())) {
                        return Mono.error(new IllegalStateException("Only FAILED documents can be retried"));
                    }
                    // 最多重试 3 次：防止反复重试无效文档浪费资源
                    if (doc.getRetryCount() != null && doc.getRetryCount() >= 3) {
                        return Mono.error(new IllegalStateException("Max retry (3) exceeded, please contact support"));
                    }
                    if (!StringUtils.hasText(doc.getObjectKey())) {
                        return markFileLostAndError(doc);
                    }
                    return objectStorageService.exists(doc.getObjectKey())
                            .flatMap(exists -> {
                                if (!exists) {
                                    return markFileLostAndError(doc);
                                }
                                // 重置为 UPLOADED 使 ETL 管道从头运行：管道不支持断点续跑，必须完整重跑
                                return Mono.fromRunnable(() -> {
                                            doc.setStatus(DocumentStatus.UPLOADED.name());
                                            doc.setErrorMessage(null);
                                            doc.setErrorStack(null);
                                            doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
                                            doc.setAclRefreshError(null);
                                            doc.setAclRefreshTime(null);
                                            doc.setUpdateDate(LocalDateTime.now());
                                            documentMapper.updateById(doc);
                                        })
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .then(etlJobService.retryDocumentIngestion(doc, doc.getObjectKey(), userId));
                            });
                });
    }

    private Mono<Void> markFileLostAndError(Document doc) {
        return Mono.fromRunnable(() -> {
                    doc.setStatus(DocumentStatus.FAILED.name());
                    doc.setErrorMessage("源文件已丢失，无法重试，请重新上传");
                    doc.setUpdateDate(LocalDateTime.now());
                    documentMapper.updateById(doc);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Void> updatePermissions(String id, DocumentPermissionUpdateRequest request) {
        return Mono.fromRunnable(() -> {
                    Document doc = documentMapper.selectById(id);
                    if (doc == null) {
                        throw new IllegalArgumentException("Document not found: " + id);
                    }
                    documentPermissionManager.applyPermissionUpdate(doc, request);
                    doc.setAclVersion(nextAclVersion(doc));
                    doc.setAclRefreshStatus(AclRefreshStatus.PENDING.name());
                    doc.setAclRefreshError(null);
                    doc.setAclRefreshTime(null);
                    doc.setUpdateDate(LocalDateTime.now());
                    documentMapper.updateById(doc);
                    aclRefreshManager.enqueue(doc);
                    boolean refreshed = aclRefreshManager.processSingle(doc.getDocUuid(), doc.getAclVersion());
                    if (!refreshed) {
                        log.warn("ACL metadata refresh queued for retry: docUuid={}, aclVersion={}",
                                doc.getDocUuid(), doc.getAclVersion());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Mono<Integer> backfillAclMetadata() {
        return Mono.fromCallable(aclRefreshManager::backfillAclMetadata)
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> closeQuietly(InputStream is) {
        return Mono.fromRunnable(() -> {
            try {
                if (is != null) {
                    is.close();
                }
            } catch (IOException e) {
                log.warn("Failed to close InputStream for downloaded document", e);
            }
        });
    }

    private int nextAclVersion(Document doc) {
        return doc.getAclVersion() == null ? 1 : doc.getAclVersion() + 1;
    }
}
