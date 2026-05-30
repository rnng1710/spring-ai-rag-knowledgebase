package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.service.impl.EtlJobLeaseService;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtlJobWorkerTest {

    @BeforeAll
    static void initMybatisPlusCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), EtlJob.class);
    }

    @Test
    void claimFailureDoesNotRunEtl() {
        WorkerFixture f = new WorkerFixture();
        EtlJob job = pendingJob();

        when(f.etlJobService.markRunning(anyString(), anyString(), any())).thenReturn(Mono.just(false));

        f.worker.processJobs(List.of(job)).block();

        verify(f.etlJobMapper, never()).selectById(anyString());
        verify(f.etlPipeline, never()).ingestionByPath(any(), anyString(), any(), any());
    }

    @Test
    void claimedJobFailsBeforeIngestionMarkedFailedWithRetryMetadata() {
        WorkerFixture f = new WorkerFixture();
        EtlJob job = expiredRunningJob();

        when(f.etlJobService.markRunning(anyString(), anyString(), any())).thenReturn(Mono.just(true));
        when(f.etlJobMapper.selectById(job.getId())).thenReturn(job);
        when(f.documentMapper.selectOne(any())).thenReturn(null);
        when(f.etlJobMapper.update(isNull(), any())).thenReturn(1);

        f.worker.processJobs(List.of(job)).block();

        ArgumentCaptor<LambdaUpdateWrapper<EtlJob>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(f.etlJobMapper).update(isNull(), wrapperCaptor.capture());

        String sqlSet = wrapperCaptor.getValue().getSqlSet().toUpperCase();
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment().toUpperCase();
        Assertions.assertTrue(sqlSet.contains("STATUS"));
        Assertions.assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(EtlJobStatus.FAILED.name()));
        Assertions.assertTrue(sqlSet.contains("RETRY_COUNT"));
        Assertions.assertTrue(sqlSet.contains("NEXT_RETRY_TIME"));
        Assertions.assertTrue(sqlSet.contains("LAST_ERROR"));
        Assertions.assertTrue(sqlSet.contains("ERROR_STACK"));
        Assertions.assertTrue(sqlSet.contains("ACTIVE_KEY"));
        Assertions.assertTrue(sqlSegment.contains("STATUS"));
        Assertions.assertTrue(wrapperCaptor.getValue().getParamNameValuePairs().containsValue(EtlJobStatus.RUNNING.name()));
        Assertions.assertTrue(sqlSegment.contains("LOCKED_BY"));

        verify(f.etlPipeline, never()).ingestionByPath(any(), anyString(), any(), any());
    }

    @Test
    void pollOnceProcessesBatchDeterministicallyWithBoundedConcurrency() throws Exception {
        WorkerFixture f = new WorkerFixture();
        ReflectionTestUtils.setField(f.worker, "batchsize", 3);
        ReflectionTestUtils.setField(f.worker, "concurrency", 2);

        EtlJob jobA = runnableJobWithFile("job-1", "doc-1");
        EtlJob jobB = runnableJobWithFile("job-2", "doc-2");
        EtlJob jobC = runnableJobWithFile("job-3", "doc-3");

        when(f.etlJobService.findRunnableJobs(3)).thenReturn(Mono.just(List.of(jobA, jobB, jobC)));
        when(f.etlJobMapper.update(isNull(), any())).thenReturn(1);
        when(f.etlJobService.markRunning(eq(jobA.getId()), anyString(), any())).thenReturn(Mono.just(true));
        when(f.etlJobService.markRunning(eq(jobB.getId()), anyString(), any())).thenReturn(Mono.just(true));
        when(f.etlJobService.markRunning(eq(jobC.getId()), anyString(), any())).thenReturn(Mono.just(true));
        when(f.etlJobMapper.selectById(jobA.getId())).thenReturn(jobA);
        when(f.etlJobMapper.selectById(jobB.getId())).thenReturn(jobB);
        when(f.etlJobMapper.selectById(jobC.getId())).thenReturn(jobC);
        when(f.documentMapper.selectOne(any())).thenReturn(storedDocument());

        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        when(f.etlPipeline.ingestionByPath(any(), anyString(), any(), any()))
                .thenAnswer(invocation -> Mono.defer(() -> {
                    int current = inFlight.incrementAndGet();
                    maxInFlight.accumulateAndGet(current, Math::max);
                    return Mono.delay(Duration.ofMillis(75))
                            .then()
                            .doFinally(signalType -> inFlight.decrementAndGet());
                }));

        f.worker.pollOnce().block();

        verify(f.etlJobService).findRunnableJobs(3);
        verify(f.etlJobService).markRunning(eq(jobA.getId()), anyString(), any());
        verify(f.etlJobService).markRunning(eq(jobB.getId()), anyString(), any());
        verify(f.etlJobService).markRunning(eq(jobC.getId()), anyString(), any());
        verify(f.etlPipeline).ingestionByPath(Path.of(jobA.getFilePath()), jobA.getDocUuid(), jobA.getCreateUserId(), jobA.getTags());
        verify(f.etlPipeline).ingestionByPath(Path.of(jobB.getFilePath()), jobB.getDocUuid(), jobB.getCreateUserId(), jobB.getTags());
        verify(f.etlPipeline).ingestionByPath(Path.of(jobC.getFilePath()), jobC.getDocUuid(), jobC.getCreateUserId(), jobC.getTags());
        Assertions.assertEquals(2, maxInFlight.get());
    }

    // -- fixtures --

    private static class WorkerFixture {
        final DocumentMapper documentMapper = mock(DocumentMapper.class);
        final EtlPipeline etlPipeline = mock(EtlPipeline.class);
        final EtlJobLeaseService leaseService = mock(EtlJobLeaseService.class);
        final EtlJobService etlJobService = mock(EtlJobService.class);
        final EtlJobMapper etlJobMapper = mock(EtlJobMapper.class);
        final ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        final EtlJobWorker worker = new EtlJobWorker(
                documentMapper, etlPipeline, leaseService, etlJobService, etlJobMapper, objectStorageService);
    }

    private static EtlJob pendingJob() {
        return pendingJob("job-1", "doc-1");
    }

    private static EtlJob pendingJob(String id, String docUuid) {
        EtlJob job = new EtlJob();
        job.setId(id);
        job.setDocUuid(docUuid);
        job.setStatus(EtlJobStatus.PENDING.name());
        job.setRetryCount(0);
        job.setMaxRetryCount(3);
        job.setCreateUserId("user-1");
        return job;
    }

    private static EtlJob runnableJobWithFile(String id, String docUuid) throws Exception {
        EtlJob job = pendingJob(id, docUuid);
        Path file = Files.createTempFile("etl-job-worker-", ".txt");
        job.setFilePath(file.toString());
        return job;
    }

    private static net.topikachu.rag.business.document.entity.Document storedDocument() {
        net.topikachu.rag.business.document.entity.Document document =
                new net.topikachu.rag.business.document.entity.Document();
        document.setDocUuid("stored-doc");
        return document;
    }

    private static EtlJob expiredRunningJob() {
        EtlJob job = pendingJob();
        job.setStatus(EtlJobStatus.RUNNING.name());
        job.setRetryCount(0);
        job.setLockedBy("previous-worker");
        job.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        job.setActiveKey("doc-1:DOCUMENT_INGESTION");
        return job;
    }
}
