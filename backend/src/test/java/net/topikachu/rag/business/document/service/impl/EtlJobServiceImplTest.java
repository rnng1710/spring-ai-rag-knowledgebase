package net.topikachu.rag.business.document.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtlJobServiceImplTest {

    @BeforeAll
    static void initMybatisPlusCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EtlJob.class);
    }

    @Test
    void findRunnableJobsIncludesExpiredRunningLeases() {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);

        when(mapper.selectList(any())).thenReturn(List.of());

        service.findRunnableJobs(10).block();

        ArgumentCaptor<LambdaQueryWrapper<EtlJob>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(wrapperCaptor.capture());

        String sql = wrapperCaptor.getValue().getSqlSegment().toUpperCase();
        Assertions.assertTrue(sql.contains("STATUS"));
        Assertions.assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(EtlJobStatus.RUNNING.name()));
        Assertions.assertTrue(sql.contains("LOCKED_UNTIL"));
        Assertions.assertTrue(sql.contains("retry_count < max_retry_count".toUpperCase()));
    }

    @Test
    void markExpiredRunningLeasesFailedUpdatesRetryMetadata() {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);
        LocalDateTime now = LocalDateTime.now();

        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(2);

        int updated = service.markExpiredRunningLeasesFailedSync(now, "lease expired", "stack");

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<EtlJob>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(mapper).update(ArgumentMatchers.isNull(), wrapperCaptor.capture());

        String sqlSet = wrapperCaptor.getValue().getSqlSet().toUpperCase();
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment().toUpperCase();
        Assertions.assertEquals(2, updated);
        Assertions.assertTrue(sqlSet.contains("STATUS"));
        Assertions.assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(EtlJobStatus.FAILED.name()));
        Assertions.assertTrue(sqlSet.contains("LAST_ERROR"));
        Assertions.assertTrue(sqlSet.contains("ERROR_STACK"));
        Assertions.assertTrue(sqlSet.contains("NEXT_RETRY_TIME = CASE"));
        Assertions.assertTrue(sqlSet.contains("ACTIVE_KEY = CASE"));
        Assertions.assertTrue(sqlSet.contains("RETRY_COUNT = IFNULL(RETRY_COUNT, 0) + 1"));
        Assertions.assertTrue(sqlSegment.contains("STATUS"));
        Assertions.assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(EtlJobStatus.RUNNING.name()));
        Assertions.assertTrue(sqlSegment.contains("LOCKED_UNTIL"));
        Assertions.assertTrue(sqlSegment.contains("RETRY_COUNT < MAX_RETRY_COUNT"));
    }

    @Test
    void markExpiredRunningLeasesFailedReactiveMethodDelegatesToSyncLogic() {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);
        LocalDateTime now = LocalDateTime.now();

        when(mapper.update(ArgumentMatchers.isNull(), any())).thenReturn(3);

        int updated = service.markExpiredRunningLeasesFailed(now, "lease expired", "stack").block();

        Assertions.assertEquals(3, updated);
        verify(mapper).update(ArgumentMatchers.isNull(), any());
    }

    @Test
    void queueDocumentIngestionSkipsWhenActiveJobAlreadyExists() throws Exception {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);
        Document doc = document();
        String objectKey = objectKey();
        EtlJob activeJob = activeJob();

        when(mapper.selectOne(any())).thenReturn(activeJob);

        service.queueDocumentIngestion(doc, objectKey, "user-1").block();

        verify(mapper, never()).insert(ArgumentMatchers.any(EtlJob.class));
    }

    @Test
    void queueDocumentIngestionTreatsDuplicateActiveKeyAsIdempotentSuccess() throws Exception {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);
        Document doc = document();
        String objectKey = objectKey();

        when(mapper.selectOne(any())).thenReturn(null, activeJob());
        when(mapper.insert(ArgumentMatchers.any(EtlJob.class)))
                .thenThrow(new DuplicateKeyException("duplicate active key"));

        service.queueDocumentIngestion(doc, objectKey, "user-1").block();

        verify(mapper).insert(ArgumentMatchers.any(EtlJob.class));
    }

    @Test
    void queueDocumentIngestionRethrowsDuplicateKeyWhenNoActiveJobCanBeFound() throws Exception {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);
        Document doc = document();
        String objectKey = objectKey();

        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(ArgumentMatchers.any(EtlJob.class)))
                .thenThrow(new DuplicateKeyException("duplicate active key"));

        assertThrows(DuplicateKeyException.class,
                () -> service.queueDocumentIngestion(doc, objectKey, "user-1").block());
    }

    private Document document() {
        Document doc = new Document();
        doc.setDocUuid("doc-1");
        doc.setFileName("test.pdf");
        return doc;
    }

    private EtlJob activeJob() {
        EtlJob job = new EtlJob();
        job.setId("job-1");
        job.setDocUuid("doc-1");
        job.setStatus(EtlJobStatus.PENDING.name());
        return job;
    }

    private String objectKey() {
        return "documents/doc-1/test.pdf";
    }
}
