package net.topikachu.rag.business.document.service.impl;

import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.KnowledgeAclRefreshTask;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.KnowledgeAclRefreshTaskMapper;
import net.topikachu.rag.service.etl.DocumentChunkMetadataBuilder;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import net.topikachu.rag.service.etl.MilvusChunkRow;
import net.topikachu.rag.service.etl.MilvusWriteGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AclRefreshManagerTest {

    private DocumentMapper documentMapper;
    private KnowledgeAclRefreshTaskMapper aclRefreshTaskMapper;
    private MilvusWriteGateway milvusWriteGateway;
    private DocumentChunkMetadataBuilder metadataBuilder;
    private KnowledgeParentBlockService parentBlockService;
    private AclRefreshManager manager;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        aclRefreshTaskMapper = mock(KnowledgeAclRefreshTaskMapper.class);
        milvusWriteGateway = mock(MilvusWriteGateway.class);
        metadataBuilder = mock(DocumentChunkMetadataBuilder.class);
        parentBlockService = mock(KnowledgeParentBlockService.class);
        manager = new AclRefreshManager(
                documentMapper,
                aclRefreshTaskMapper,
                milvusWriteGateway,
                metadataBuilder,
                parentBlockService);
        ReflectionTestUtils.setField(manager, "aclRefreshMaxRetries", 5);
        ReflectionTestUtils.setField(manager, "aclRefreshBatchSize", 20);
    }

    @Test
    void staleAclVersionDeletesTaskAndSkipsRefresh() {
        KnowledgeAclRefreshTask task = task("task1", "doc1", 1);
        Document doc = doc("id1", "doc1", 2);
        when(aclRefreshTaskMapper.selectOne(any())).thenReturn(task);
        when(documentMapper.selectOne(any())).thenReturn(doc);

        boolean refreshed = manager.processSingle("doc1", 1);

        assert !refreshed;
        verify(aclRefreshTaskMapper).deleteById("task1");
        verify(milvusWriteGateway, never()).queryChunksByDocUuid(any());
    }

    @Test
    void successfulRefreshMarksTaskAndDocumentSuccess() {
        KnowledgeAclRefreshTask task = task("task1", "doc1", 1);
        Document doc = doc("id1", "doc1", 1);
        when(aclRefreshTaskMapper.selectOne(any())).thenReturn(task);
        when(documentMapper.selectOne(any())).thenReturn(doc);
        when(aclRefreshTaskMapper.update(any(), any())).thenReturn(1);
        when(milvusWriteGateway.queryChunksByDocUuid("doc1"))
                .thenReturn(Mono.just(List.of(new MilvusChunkRow(
                        "chunk1", "content", Map.of("old", "metadata"), List.of(0.1f), Map.of("k", 1)))));
        when(metadataBuilder.build(any(), any(), any(), any(), any(), any()))
                .thenReturn(Map.of("doc_uuid", "doc1"));
        when(milvusWriteGateway.upsert(any())).thenReturn(Mono.empty());
        when(parentBlockService.refreshMetadata(doc)).thenReturn(Mono.empty());

        boolean refreshed = manager.processSingle("doc1", 1);

        assert refreshed;
        assert AclRefreshStatus.SUCCESS.name().equals(task.getStatus());
        assert AclRefreshStatus.SUCCESS.name().equals(doc.getAclRefreshStatus());
        verify(milvusWriteGateway).upsert(any());
        verify(parentBlockService).refreshMetadata(doc);
    }

    @Test
    void failedRefreshMarksTaskAndDocumentFailed() {
        KnowledgeAclRefreshTask task = task("task1", "doc1", 1);
        Document doc = doc("id1", "doc1", 1);
        when(aclRefreshTaskMapper.selectOne(any())).thenReturn(task);
        when(documentMapper.selectOne(any())).thenReturn(doc);
        when(aclRefreshTaskMapper.update(any(), any())).thenReturn(1);
        when(milvusWriteGateway.queryChunksByDocUuid("doc1")).thenReturn(Mono.just(List.of()));

        boolean refreshed = manager.processSingle("doc1", 1);

        assert !refreshed;
        assert AclRefreshStatus.FAILED.name().equals(task.getStatus());
        assert task.getNextRetryTime() != null;
        assert AclRefreshStatus.FAILED.name().equals(doc.getAclRefreshStatus());
        verify(milvusWriteGateway, never()).upsert(any());
    }

    @Test
    void failedClaimSkipsMilvusRefresh() {
        KnowledgeAclRefreshTask task = task("task1", "doc1", 1);
        Document doc = doc("id1", "doc1", 1);
        when(aclRefreshTaskMapper.selectOne(any())).thenReturn(task);
        when(documentMapper.selectOne(any())).thenReturn(doc);
        when(aclRefreshTaskMapper.update(any(), any())).thenReturn(0);

        boolean refreshed = manager.processSingle("doc1", 1);

        assert !refreshed;
        verify(milvusWriteGateway, never()).queryChunksByDocUuid(any());
    }

    private KnowledgeAclRefreshTask task(String id, String docUuid, int aclVersion) {
        KnowledgeAclRefreshTask task = new KnowledgeAclRefreshTask();
        task.setId(id);
        task.setDocUuid(docUuid);
        task.setTargetAclVersion(aclVersion);
        task.setStatus(AclRefreshStatus.PENDING.name());
        task.setRetryCount(0);
        return task;
    }

    private Document doc(String id, String docUuid, int aclVersion) {
        Document doc = new Document();
        doc.setId(id);
        doc.setDocUuid(docUuid);
        doc.setFileName("test.pdf");
        doc.setAclVersion(aclVersion);
        doc.setTags(List.of("tag1"));
        return doc;
    }
}
