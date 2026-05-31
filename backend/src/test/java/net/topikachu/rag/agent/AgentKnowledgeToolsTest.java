package net.topikachu.rag.agent;

import net.topikachu.rag.service.chat.ReactiveChatGateway;
import net.topikachu.rag.service.chat.ParentContextBlock;
import net.topikachu.rag.service.chat.ParentContextMissingException;
import net.topikachu.rag.service.chat.RetrievalResult;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import org.springframework.ai.document.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentKnowledgeToolsTest {

    @Mock
    private AgentKnowledgeService knowledgeService;

    @Mock
    private AgentToolBridge toolBridge;

    @Mock
    private ReactiveChatGateway reactiveChatGateway;

    @Mock
    private ChatModelStrategy strategy;

    @Mock
    private org.springframework.ai.chat.client.ChatClient chatClient;

    @Test
    void searchKnowledgeSnippetsReturnsParentContextsAndStoresEvidence() {
        AgentExecutionContext executionContext = newExecutionContext();
        AgentKnowledgeTools tools = newTools(executionContext);
        RetrievalResult retrievalResult = new RetrievalResult(
                List.of(child("ev-1", "child text", "parent-1")),
                List.of(new ParentContextBlock(
                        "parent-1",
                        "doc-1",
                        "policy.pdf",
                        "parent full text",
                        3,
                        7,
                        7,
                        List.of("ev-1"),
                        1)));

        when(knowledgeService.searchKnowledgeSnippets(anyString(), any(), any(), any()))
                .thenReturn(Mono.just(retrievalResult));
        when(toolBridge.block(any(), any(), anyString())).thenReturn(retrievalResult);

        KnowledgeSearchResult result = tools.searchKnowledgeSnippets(new KnowledgeSearchRequest("问题", List.of(), 5));

        assertEquals("ok", result.status());
        assertEquals(1, result.items().size());
        KnowledgeSnippet item = result.items().get(0);
        assertEquals("parent-1", item.parentBlockId());
        assertEquals("parent full text", item.text());
        assertEquals(List.of("ev-1"), item.citableEvidenceIds());
        assertEquals("doc-1", item.metadataSnapshot().get("doc_uuid"));
        assertEquals(1, executionContext.retrievedEvidence().size());
        assertEquals(1, executionContext.retrievedParentContexts().size());
    }

    @Test
    void searchKnowledgeSnippetsReportsIndexRebuildWhenParentLookupFails() {
        AgentKnowledgeTools tools = newTools();
        ParentContextMissingException error = new ParentContextMissingException("知识库索引数据不一致，请重建该文档索引后重试。");

        when(knowledgeService.searchKnowledgeSnippets(anyString(), any(), any(), any()))
                .thenReturn(Mono.error(error));
        when(toolBridge.block(any(), any(), anyString())).thenThrow(error);

        KnowledgeSearchResult result = tools.searchKnowledgeSnippets(new KnowledgeSearchRequest("问题", List.of(), 5));

        assertEquals("tool_error", result.status());
        assertEquals("INDEX_REBUILD_REQUIRED", result.errorCode());
    }

    @Test
    void generateFollowupOptionsFailsWithoutRetrievalHistory() {
        AgentKnowledgeTools tools = newTools();

        FollowupOptionsResult result = tools.generateFollowupOptions();

        assertEquals("tool_error", result.status());
        assertEquals("SEARCH_REQUIRED", result.errorCode());
    }

    @Test
    void generateFollowupOptionsReturnsTwoNormalizedQuestions() {
        AgentExecutionContext executionContext = newExecutionContext();
        stubChatClient();
        AgentKnowledgeTools tools = newTools(executionContext);

        executionContext.incrementSearchInvocationCount();
        executionContext.recordRetrieval("原问题", "原问题||5", List.of(), 5, "ok", 2, List.of("e1", "e2"));
        executionContext.updateRetrievalAssessment(RetrievalGapType.MISSING_SCOPE, true, List.of("scope", "procedure"));
        executionContext.addRetrievedEvidence(List.of(
                new EvidenceSnapshot("e1", "证据一", java.util.Map.of("file_name", "doc1")),
                new EvidenceSnapshot("e2", "证据二", java.util.Map.of("file_name", "doc2"))), 12);
        executionContext.addRetrievedParentContexts(List.of(
                new ParentContextBlock("p1", "doc-1", "doc1", "父块一", 1, null, null, List.of("e1", "e2"), 1)));

        FollowupOptionsResult raw = FollowupOptionsResult.ok(
                List.of("围绕适用对象进一步检索", "围绕审批流程继续检索？"),
                List.of("scope", "procedure"),
                "debug");
        when(reactiveChatGateway.callStructured(any(), anyString(), anyMap(), anyString(), eq(FollowupOptionsResult.class)))
                .thenReturn(Mono.just(raw));
        when(toolBridge.block(any(), any(), anyString())).thenReturn(raw);

        FollowupOptionsResult result = tools.generateFollowupOptions();

        assertEquals("ok", result.status());
        assertEquals(2, result.options().size());
        assertEquals(2, result.focusTypes().size());
        assertTrue(result.options().get(0).endsWith("？"));
        assertEquals(List.of("scope", "procedure"), result.focusTypes());
    }

    private AgentKnowledgeTools newTools() {
        return newTools(newExecutionContext());
    }

    private AgentKnowledgeTools newTools(AgentExecutionContext executionContext) {
        return new AgentKnowledgeTools(
                knowledgeService,
                executionContext,
                toolBridge,
                reactiveChatGateway,
                strategy,
                Duration.ofSeconds(2),
                6,
                12,
                2);
    }

    private AgentExecutionContext newExecutionContext() {
        return new AgentExecutionContext("req-1", "conv-1", "msg-1", "原问题", List.of("tag1"));
    }

    private Document child(String evidenceId, String text, String parentBlockId) {
        return Document.builder()
                .id(evidenceId)
                .text(text)
                .metadata(Map.of(
                        "evidence_id", evidenceId,
                        "parent_block_id", parentBlockId,
                        "doc_uuid", "doc-1",
                        "file_name", "policy.pdf"))
                .build();
    }

    private void stubChatClient() {
        when(strategy.getChatClient()).thenReturn(chatClient);
    }
}
