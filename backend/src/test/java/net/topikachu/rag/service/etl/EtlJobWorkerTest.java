package net.topikachu.rag.service.etl;

import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.service.impl.EtlJobLeaseService;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtlJobWorkerTest {

    @Test
    void pollDoesNotRunEtlWhenClaimFails() {
        DocumentMapper documentMapper = mock(DocumentMapper.class);
        EtlPipeline etlPipeline = mock(EtlPipeline.class);
        EtlJobLeaseService leaseService = mock(EtlJobLeaseService.class);
        EtlJobService etlJobService = mock(EtlJobService.class);
        EtlJobMapper etlJobMapper = mock(EtlJobMapper.class);
        ObjectStorageService objectStorageService = mock(ObjectStorageService.class);
        EtlJobWorker worker = new EtlJobWorker(documentMapper, etlPipeline, leaseService, etlJobService, etlJobMapper, objectStorageService);
        EtlJob job = job();

        when(etlJobService.findRunnableJobs(0)).thenReturn(List.of(job));
        when(etlJobService.markRunning(anyString(), anyString(), any())).thenReturn(false);

        worker.poll();

        verify(etlJobMapper, never()).selectById(anyString());
        verify(etlPipeline, never()).ingestionByPath(any(), anyString(), any(), any());
    }

    private EtlJob job() {
        EtlJob job = new EtlJob();
        job.setId("job-1");
        job.setDocUuid("doc-1");
        job.setStatus(EtlJobStatus.PENDING.name());
        job.setRetryCount(0);
        job.setMaxRetryCount(3);
        job.setCreateUserId("user-1");
        return job;
    }
}
