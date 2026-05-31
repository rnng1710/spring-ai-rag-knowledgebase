package net.topikachu.rag.service.etl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.business.document.entity.AclRefreshStatus;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EtlStatusManagerTest {

    @BeforeAll
    static void initMybatisPlusCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, ""),
                net.topikachu.rag.business.document.entity.Document.class);
    }

    private net.topikachu.rag.business.document.mapper.DocumentMapper documentMapper;
    private HybridVectorWriter hybridVectorWriter;
    private KnowledgeParentBlockService parentBlockService;
    private StringRedisTemplate redisTemplate;
    private ObjectMapper objectMapper;
    private EtlStatusManager statusManager;

    @BeforeEach
    void setUp() {
        documentMapper = mock(net.topikachu.rag.business.document.mapper.DocumentMapper.class);
        hybridVectorWriter = mock(HybridVectorWriter.class);
        parentBlockService = mock(KnowledgeParentBlockService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        objectMapper = new ObjectMapper();
        statusManager = new EtlStatusManager(documentMapper, hybridVectorWriter, parentBlockService, redisTemplate, objectMapper);
        when(parentBlockService.deleteByDocUuid(anyString())).thenReturn(Mono.empty());
    }

    // -- transitionTo --

    @Test
    void transitionTo_success() throws Exception {
        when(documentMapper.update(isNull(), any())).thenReturn(1);

        statusManager.transitionTo("doc-1", "user-1", DocumentStatus.READING, "reading").block();

        verify(documentMapper).update(isNull(), any());
        verify(redisTemplate).convertAndSend(eq("topic:etl-status"), anyString());
    }

    @Test
    void transitionTo_nullDocUuid() {
        Mono<Void> result = statusManager.transitionTo(null, "user-1", DocumentStatus.READING, "msg");
        assertEquals(Mono.empty(), result);
        verify(documentMapper, never()).update(isNull(), any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    // -- transitionToCompleted --

    @Test
    void transitionToCompleted_success() {
        when(documentMapper.update(isNull(), any())).thenReturn(1);

        statusManager.transitionToCompleted("doc-1", "user-1").block();

        verify(documentMapper).update(isNull(), any());
        verify(redisTemplate).convertAndSend(eq("topic:etl-status"), anyString());
    }

    @Test
    void transitionToCompleted_nullDocUuid() {
        Mono<Void> result = statusManager.transitionToCompleted(null, "user-1");
        assertEquals(Mono.empty(), result);
        verify(documentMapper, never()).update(isNull(), any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void transitionToCompleted_conflict() {
        when(documentMapper.update(isNull(), any())).thenReturn(0);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> statusManager.transitionToCompleted("doc-1", "user-1").block());

        assertTrue(error.getMessage().contains("CAS conflict"));
        assertTrue(error.getMessage().contains("FAILED"));
    }

    // -- transitionToFailed --

    @Test
    void transitionToFailed_success() {
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(hybridVectorWriter.deleteByDocUuid("doc-1")).thenReturn(Mono.empty());

        Boolean result = statusManager.transitionToFailed("doc-1", "user-1",
                new RuntimeException("test error")).block();

        assertTrue(result);
        verify(documentMapper).update(isNull(), any());
        verify(hybridVectorWriter).deleteByDocUuid("doc-1");
        verify(parentBlockService).deleteByDocUuid("doc-1");
        verify(redisTemplate).convertAndSend(eq("topic:etl-status"), anyString());
    }

    @Test
    void transitionToFailed_terminalState() {
        when(documentMapper.update(isNull(), any())).thenReturn(0);

        Boolean result = statusManager.transitionToFailed("doc-1", "user-1",
                new RuntimeException("no-op")).block();

        assertEquals(false, result);
        verify(hybridVectorWriter, never()).deleteByDocUuid(any());
        verify(redisTemplate, never()).convertAndSend(anyString(), anyString());
    }

    @Test
    void transitionToFailed_nullDocUuid() {
        Boolean result = statusManager.transitionToFailed(null, "user-1",
                new RuntimeException("err")).block();

        assertEquals(false, result);
        verify(documentMapper, never()).update(isNull(), any());
        verify(hybridVectorWriter, never()).deleteByDocUuid(any());
    }

    @Test
    void transitionToFailed_cleanupFailure() {
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(hybridVectorWriter.deleteByDocUuid("doc-1"))
                .thenReturn(Mono.error(new RuntimeException("milvus down")));

        Boolean result = statusManager.transitionToFailed("doc-1", "user-1",
                new RuntimeException("test")).block();

        assertTrue(result);
        verify(redisTemplate).convertAndSend(eq("topic:etl-status"), anyString());
    }

    // -- transitionToFailedSync --

    @Test
    void transitionToFailedSync_success() {
        when(documentMapper.update(isNull(), any())).thenReturn(1);
        when(hybridVectorWriter.deleteByDocUuid("doc-1")).thenReturn(Mono.empty());

        boolean result = statusManager.transitionToFailedSync("doc-1", "user-1",
                new RuntimeException("sync"));

        assertTrue(result);
        verify(documentMapper).update(isNull(), any());
        verify(hybridVectorWriter).deleteByDocUuid("doc-1");
    }

    @Test
    void transitionToFailedSync_terminalState() {
        when(documentMapper.update(isNull(), any())).thenReturn(0);

        boolean result = statusManager.transitionToFailedSync("doc-1", "user-1",
                new RuntimeException("sync"));

        assertEquals(false, result);
        verify(hybridVectorWriter, never()).deleteByDocUuid(any());
    }
}
