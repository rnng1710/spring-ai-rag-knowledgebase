package net.topikachu.rag.service.etl;

import com.google.gson.JsonObject;
import io.milvus.v2.service.vector.response.InsertResp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HybridVectorWriterTest {

    @Mock
    private TeiEmbeddingClient teiEmbeddingClient;

    @Mock
    private MilvusWriteGateway milvusWriteGateway;

    private HybridVectorWriter hybridVectorWriter;

    @BeforeEach
    void setUp() {
        hybridVectorWriter = new HybridVectorWriter(teiEmbeddingClient, milvusWriteGateway);
    }

    @Test
    void skipsFailedChunksAndInsertsRemainingRows() {
        Document good = new Document("正常内容", Map.of("doc_uuid", "doc-1", "page_number", 1));
        Document bad = new Document("坏内容", Map.of("doc_uuid", "doc-1", "page_number", 2));

        when(teiEmbeddingClient.embed("正常内容"))
                .thenReturn(Mono.just(new TeiEmbeddingClient.BgeM3Response(
                        List.of(List.of(1.0f, 2.0f)),
                        List.of(Map.of("1", 0.8f)))));
        when(teiEmbeddingClient.embed("坏内容"))
                .thenReturn(Mono.error(new IllegalArgumentException("invalid chunk")));

        InsertResp insertResp = mock(InsertResp.class);
        when(insertResp.getInsertCnt()).thenReturn(1L);
        when(milvusWriteGateway.insert(anyList())).thenReturn(Mono.just(insertResp));

        assertDoesNotThrow(() -> hybridVectorWriter.write(List.of(good, bad)).block(Duration.ofSeconds(2)));

        ArgumentCaptor<List<JsonObject>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(milvusWriteGateway).insert(rowsCaptor.capture());
        List<JsonObject> rows = rowsCaptor.getValue();
        assertEquals(1, rows.size());
        assertEquals("正常内容", rows.get(0).get("content").getAsString());
    }

    @Test
    void failsWhenAllChunksFailEmbedding() {
        Document bad = new Document("坏内容", Map.of("doc_uuid", "doc-2", "page_number", 9));

        when(teiEmbeddingClient.embed("坏内容"))
                .thenReturn(Mono.error(new IllegalArgumentException("invalid chunk")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> hybridVectorWriter.write(List.of(bad)).block(Duration.ofSeconds(2)));
        assertTrue(error instanceof IllegalStateException);
        assertEquals("All document chunks failed embedding", error.getMessage());

        verify(milvusWriteGateway, never()).insert(anyList());
    }
}
