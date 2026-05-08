package net.topikachu.rag.business.document.service.impl;

import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtlJobServiceImplTest {

    @Test
    void queueDocumentIngestionSkipsWhenActiveJobAlreadyExists() throws Exception {
        EtlJobMapper mapper = mock(EtlJobMapper.class);
        EtlJobServiceImpl service = new EtlJobServiceImpl(mapper);
        Document doc = document();
        String objectKey = objectKey();
        EtlJob activeJob = activeJob();

        when(mapper.selectOne(any())).thenReturn(activeJob);

        service.queueDocumentIngestion(doc, objectKey, "user-1");

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

        service.queueDocumentIngestion(doc, objectKey, "user-1");

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
                () -> service.queueDocumentIngestion(doc, objectKey, "user-1"));
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
