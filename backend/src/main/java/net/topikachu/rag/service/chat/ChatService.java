package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.SearchScope;
import net.topikachu.rag.evaluation.ContextNode;
import net.topikachu.rag.evaluation.EvaluationConfig;
import net.topikachu.rag.evaluation.EvaluationResultItem;
import net.topikachu.rag.evaluation.service.EvaluationPersistenceService;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Chat service with hybrid search (dense + sparse) and reranking.
 */
@Service
@Slf4j
public class ChatService {

    private final ChatMemory chatMemory;
    private final RetrievalPipeline retrievalPipeline;
    private final ContextFormatter contextFormatter;
    private final ChatModelStrategyFactory strategyFactory;
    private final ReactiveChatGateway reactiveChatGateway;
    private final TracingSupport tracingSupport;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;
    private final EvaluationPersistenceService persistenceService;
    private final UsedSourceValidator usedSourceValidator;

    @Value("${rag.retrieval.hybrid-topk:20}")
    private int hybridTopK;

    @Value("${rag.retrieval.rerank-topk:10}")
    private int rerankTopK;

    @Value("${rag.retrieval.max-context-chars:40000}")
    private int maxContextChars;

    public ChatService(ChatMemory chatMemory,
            RetrievalPipeline retrievalPipeline,
            ContextFormatter contextFormatter,
            ChatModelStrategyFactory strategyFactory,
            ReactiveChatGateway reactiveChatGateway,
            TracingSupport tracingSupport,
            EvaluationPersistenceService persistenceService,
            UsedSourceValidator usedSourceValidator) {
        this.chatMemory = chatMemory;
        this.retrievalPipeline = retrievalPipeline;
        this.contextFormatter = contextFormatter;
        this.strategyFactory = strategyFactory;
        this.reactiveChatGateway = reactiveChatGateway;
        this.tracingSupport = tracingSupport;
        this.persistenceService = persistenceService;
        this.usedSourceValidator = usedSourceValidator;

        this.messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    public record ChatStreamResponse(Flux<String> flux, List<UsedSource> usedSources) {
    }

    public Mono<List<Document>> retrieveForEvaluation(String query, boolean useSparseSearch, boolean useRerank, int topK) {
        int fetchK = useRerank ? hybridTopK : topK;
        return retrievalPipeline.retrieve(query, fetchK, topK, useSparseSearch, useRerank);
    }

    // TODO:: 精确计算token消耗量
    public Mono<ChatStreamResponse> streamWithSources(String userInput, String conversationId,
            CurrentUserContext currentUserContext, SearchScope searchScope,
            String modelId, String msgId) {
        log.info("Processing query: '{}', conversationId: {}, spaces: {}, tags: {}, modelId: {}, user={}",
                userInput, conversationId,
                searchScope == null ? List.of() : searchScope.requestedSpaceCodes(),
                searchScope == null ? List.of() : searchScope.requestedTags(),
                modelId,
                currentUserContext == null ? null : currentUserContext.username());

        String traceId = tracingSupport.getCurrentTraceId();

        return retrievalPipeline.retrieveWithParentContexts(userInput, currentUserContext, searchScope, hybridTopK, rerankTopK,
                        Map.of(
                                "chat.mode", "rag",
                                "chat.model_id", modelId == null ? "" : modelId,
                                "chat.conversation_id", conversationId == null ? "" : conversationId))
                .flatMap(retrievalResult -> tracingSupport.traceMono("rag.context_build",
                                Map.of(
                                        "chat.mode", "rag",
                                        "chat.model_id", modelId == null ? "" : modelId,
                                        "rag.source_count", retrievalResult.childCandidates().size(),
                                        "rag.parent_context_count", retrievalResult.parentContexts().size(),
                                        "rag.max_context_chars", maxContextChars),
                                Mono.fromSupplier(() -> contextFormatter.formatParentContexts(retrievalResult.parentContexts())))
                        .flatMap(context -> {
                            ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);
                            return reactiveChatGateway.callStructured(
                                            strategy.getChatClient(),
                                            buildSourcedAnswerPrompt(),
                                            Map.of("context", context),
                                            userInput,
                                            conversationId,
                                            messageChatMemoryAdvisor,
                                            SourcedAnswerResult.class)
                                    .onErrorMap(this::toSourceValidationError)
                                    .map(result -> {
                                        List<UsedSource> usedSources = usedSourceValidator.validate(
                                                result, retrievalResult.childCandidates());
                                        String answer = result.answer();
                                        persistenceService.saveConversation(
                                                msgId, conversationId, currentUserContext.userId(),
                                                userInput, answer, modelId, "rag",
                                                toContextNodes(retrievalResult.childCandidates()), usedSources, traceId)
                                                .subscribe();
                                        return new ChatStreamResponse(Flux.just(answer), usedSources);
                                    });
                        }));
    }

    static String buildSourcedAnswerPrompt() {
        return """
                你是一个专业的“校园智能知识库问答助手”。你必须基于【知识库上下文】回答。

                必须遵守：
                1. 只能使用【知识库上下文】中的事实，不得编造或外推。
                2. 如果知识库证据不足，answerType 输出 refusal，answer 简洁说明无法可靠回答，usedSources 输出 []。
                3. 如果输出事实性回答，answerType 输出 factual，usedSources 至少包含一个来源。
                4. 每个事实段落或列表项末尾必须带引用，格式为《文件名》第 X 页；没有页码时用《文件名》片段 N。
                5. 每个事实段落或列表项最多展示 2 个引用。
                6. usedSources 必须是字符串数组；每个字符串都必须来自上下文“可引用 evidence_id”，不能创造新的 evidenceId。
                7. 你必须且只能输出合法 JSON 对象，不要输出 Markdown 代码块或额外文字。
                8. JSON 字段固定为 answer、answerType、usedSources。
                9. answer 必填且不能为空；answerType 只能是 factual 或 refusal。
                10. usedSources 只输出字符串数组，例如 ["docUuid:child:1:hash"]；不要输出对象数组，不要输出 docUuid、fileName、pageNumber、fileType，也不要输出 parent_block_id。
                11. 输出必须是单个 JSON object；第一个字符是英文左花括号，最后一个字符是英文右花括号。
                12. factual 时 answerType=factual，answer 中必须包含段落引用，usedSources 必须列出实际采用的 evidenceId。
                13. refusal 时 answerType=refusal，answer 说明当前知识库没有足够信息，usedSources 必须是空数组。
                14. 不要输出内部思考、解释、代码块或 JSON 之外的任何文字。

                ================ 知识库上下文 ================
                {context}
                ============================================
                """;
    }

    private Throwable toSourceValidationError(Throwable error) {
        if (error instanceof SourceValidationException) {
            return error;
        }
        if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().contains("structured")) {
            log.warn("Structured RAG response parse failed: {}. Cause: {}",
                    error.getMessage(),
                    error.getCause() != null ? error.getCause().getMessage() : "no cause");
            return new SourceValidationException(UsedSourceValidator.UNRELIABLE_SOURCE_MESSAGE, "json_parse_failed");
        }
        return error;
    }

    /**
     * Non-streaming, strict evaluation method used by AblationStudyRunner.
     * Extracts ContextNodes with metadata and applies dynamic evaluation
     * configurations.
     */
    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            String modelId) {
        return evaluateQuery(question, groundTruth, config, baselinePromptResource, optimizedPromptResource, rerankTopK,
                modelId, true);
    }

    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            int topK,
            String modelId) {
        return evaluateQuery(question, groundTruth, config, baselinePromptResource, optimizedPromptResource, topK,
                modelId, true);
    }

    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            int topK,
            String modelId,
            boolean allowModelFallback) {
        long startTime = System.currentTimeMillis();

        return retrieveForEvaluation(question, config.useSparseSearch(), config.useRerank(), topK)
                .flatMap(docs -> Mono.defer(() -> {
                    List<ContextNode> contextNodes = new ArrayList<>();
                    StringBuilder contextTextBuilder = new StringBuilder();

                    for (int i = 0; i < docs.size(); i++) {
                        Document doc = docs.get(i);
                        String fileName = (String) doc.getMetadata().getOrDefault("file_name", "Unknown File");
                        Object scoreObj = doc.getMetadata().get("score");
                        Double score = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;

                        contextNodes.add(new ContextNode(doc.getText(), fileName, score));

                        String structuredEntry = String.format("【文档来源: %s】\n内容: %s\n------------------------\n",
                                fileName, doc.getText());

                        if (contextTextBuilder.length() + structuredEntry.length() > maxContextChars) {
                            break;
                        }
                        contextTextBuilder.append(structuredEntry);
                    }

                    String contextText = contextTextBuilder.toString();
                    String systemPromptText = loadPromptText(config, baselinePromptResource, optimizedPromptResource);

                    return reactiveChatGateway.call(
                                    strategyFactory.getStrategy(modelId).getChatClient(),
                                    systemPromptText,
                                    Map.of("context", contextText, "question", question),
                                    question)
                            .map(generatedAnswer -> {
                                log.info("Evaluation query completed in {}ms. Length of answer: {}",
                                        System.currentTimeMillis() - startTime, generatedAnswer.length());
                                return new EvaluationResultItem(
                                        question,
                                        groundTruth,
                                        generatedAnswer,
                                        contextNodes);
                            });
                })
                .retryWhen(reactor.util.retry.Retry.backoff(3, java.time.Duration.ofSeconds(2))
                        .filter(throwable -> {
                            // Can add specific sub-exceptions if needed, but for now retry on most runtime
                            // exceptions from APIs
                            return throwable instanceof RuntimeException;
                        })
                        .doBeforeRetry(retrySignal -> log.warn("[{}] API error '{}', retrying... ({}/{})",
                                modelId, retrySignal.failure().getMessage(), retrySignal.totalRetriesInARow() + 1, 3)))
                .onErrorResume(e -> {
                    log.error("[{}] API definitively failed after retries: {}", modelId, e.getMessage());
                    if (allowModelFallback && !"ollama".equals(modelId)) {
                        log.warn("=== 触发降级机制 === 切换至本地大模型 (ollama) 作兜底回复");
                        // Recursively call evaluateQuery but force the modelId to be 'ollama'
                        return evaluateQuery(question, groundTruth, config, baselinePromptResource,
                                optimizedPromptResource, topK, "ollama", true);
                    }
                    if (!allowModelFallback) {
                        return Mono.error(e);
                    }
                    // Ultimate fallback if even Ollama fails or if the original request was already for Ollama
                    return Mono.just(new EvaluationResultItem(
                            question,
                            groundTruth,
                            "【系统提示】所有模型调用均失败，请稍后再试或联系管理员。错误详情: " + e.getMessage(),
                            new ArrayList<>()));
                }));
    }

    private String loadPromptText(EvaluationConfig config, Resource baselinePromptResource,
            Resource optimizedPromptResource) {
        try {
            Resource promptResource = config.useOptimizedPrompt()
                    ? optimizedPromptResource
                    : baselinePromptResource;
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            log.error("Failed to load prompt template, falling back to default.", e);
            return "Context:\n{context}";
        }
    }

    private List<ContextNode> toContextNodes(List<Document> docs) {
        List<ContextNode> nodes = new ArrayList<>();
        for (Document doc : docs) {
            String fileName = (String) doc.getMetadata().getOrDefault("file_name", "Unknown File");
            Object scoreObj = doc.getMetadata().get("score");
            Double score = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;
            nodes.add(new ContextNode(doc.getText(), fileName, score));
        }
        return nodes;
    }
}
