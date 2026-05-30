package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtlWatchdogTest {

    @BeforeAll
    static void initMybatisPlusCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), Document.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""), EtlJob.class);
    }

    private DocumentMapper documentMapper;
    private EtlJobMapper etlJobMapper;
    private EtlJobService etlJobService;
    private EtlStatusManager etlStatusManager;
    private EtlWatchdog watchdog;

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        etlJobMapper = mock(EtlJobMapper.class);
        etlJobService = mock(EtlJobService.class);
        etlStatusManager = mock(EtlStatusManager.class);
        watchdog = new EtlWatchdog(documentMapper, etlJobMapper, etlJobService, etlStatusManager);
        when(etlJobService.markExpiredRunningLeasesFailedSync(any(), any(), isNull())).thenReturn(0);
    }

    @Test
    void cleanupStuckDocumentsMarksStaleDocumentFailedAndTriggersVectorCleanup() {
        Document staleDoc = staleDocument();

        when(documentMapper.selectList(any())).thenReturn(List.of(staleDoc));
        when(etlJobMapper.selectCount(any())).thenReturn(0L);
        when(etlStatusManager.transitionToFailedSync(eq(staleDoc.getDocUuid()), eq(null), any()))
                .thenReturn(true);

        watchdog.cleanupStuckDocuments();

        verify(etlJobService).markExpiredRunningLeasesFailedSync(any(), any(), isNull());
        verify(etlJobMapper).selectCount(any());
        verify(etlStatusManager).transitionToFailedSync(eq(staleDoc.getDocUuid()), eq(null), any());
    }

    @Test
    void cleanupStuckDocumentsIgnoresStaleDocumentWhenActiveJobExists() {
        Document staleDoc = staleDocument();

        when(documentMapper.selectList(any())).thenReturn(List.of(staleDoc));
        when(etlJobMapper.selectCount(any())).thenReturn(1L);

        watchdog.cleanupStuckDocuments();

        verify(etlJobService).markExpiredRunningLeasesFailedSync(any(), any(), isNull());
        verify(documentMapper, never()).update(any(), any());
        verify(etlStatusManager, never()).transitionToFailedSync(any(), any(), any());
    }

    @Test
    void cleanupStuckDocumentsSkipsVectorCleanupWhenDocumentCasUpdateFails() {
        Document staleDoc = staleDocument();

        when(documentMapper.selectList(any())).thenReturn(List.of(staleDoc));
        when(etlJobMapper.selectCount(any())).thenReturn(0L);
        when(etlStatusManager.transitionToFailedSync(eq(staleDoc.getDocUuid()), eq(null), any()))
                .thenReturn(false);

        watchdog.cleanupStuckDocuments();

        verify(etlJobService).markExpiredRunningLeasesFailedSync(any(), any(), isNull());
        verify(etlStatusManager).transitionToFailedSync(eq(staleDoc.getDocUuid()), eq(null), any());
    }

    @Test
    void cleanupStuckDocumentsDoesNothingWhenFallbackFindsNoStaleDocuments() {
        when(documentMapper.selectList(any())).thenReturn(List.of());

        watchdog.cleanupStuckDocuments();

        verify(etlJobService).markExpiredRunningLeasesFailedSync(any(), any(), isNull());
        verify(documentMapper, never()).update(any(), any());
        verify(etlJobMapper, never()).selectCount(any());
        verify(etlStatusManager, never()).transitionToFailedSync(any(), any(), any());
    }

    @Test
    void cleanupStuckDocumentsContinuesFallbackWhenLeaseCleanupFails() {
        Document staleDoc = staleDocument();

        when(etlJobService.markExpiredRunningLeasesFailedSync(any(), any(), isNull()))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(documentMapper.selectList(any())).thenReturn(List.of(staleDoc));
        when(etlJobMapper.selectCount(any())).thenReturn(0L);
        when(etlStatusManager.transitionToFailedSync(eq(staleDoc.getDocUuid()), eq(null), any()))
                .thenReturn(true);

        watchdog.cleanupStuckDocuments();

        verify(etlJobService).markExpiredRunningLeasesFailedSync(any(), any(), isNull());
        verify(etlJobMapper).selectCount(any());
        verify(etlStatusManager).transitionToFailedSync(eq(staleDoc.getDocUuid()), eq(null), any());
    }

    private Document staleDocument() {
        Document doc = new Document();
        doc.setDocUuid("doc-1");
        doc.setStatus(DocumentStatus.VECTORIZING.name());
        doc.setUpdateDate(LocalDateTime.now().minusMinutes(20));
        return doc;
    }
}
