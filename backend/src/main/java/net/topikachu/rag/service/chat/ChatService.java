package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.ContextNode;
import net.topikachu.rag.evaluation.EvaluationConfig;
import net.topikachu.rag.evaluation.EvaluationResultItem;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

/**
 * Chat service with hybrid search (dense + sparse) and reranking.
 */
@Service
@Slf4j
public class ChatService {

    private final ChatMemory chatMemory;
    private final HybridSearchService hybridSearchService;
    private final RerankService rerankService;
    private final ChatModelStrategyFactory strategyFactory;
    private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;

    @Value("${rag.retrieval.hybrid-topk:20}")
    private int hybridTopK;

    @Value("${rag.retrieval.rerank-topk:10}")
    private int rerankTopK;

    @Value("${rag.retrieval.max-context-chars:8000}")
    private int maxContextChars;

    public ChatService(ChatMemory chatMemory,
            HybridSearchService hybridSearchService, RerankService rerankService,
            ChatModelStrategyFactory strategyFactory) {
        this.chatMemory = chatMemory;
        this.hybridSearchService = hybridSearchService;
        this.rerankService = rerankService;
        this.strategyFactory = strategyFactory;

        this.messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    public record ChatStreamResponse(Flux<String> flux, List<Document> sources) {
    }

    public Mono<List<Document>> retrieveForEvaluation(String query, boolean useSparseSearch, boolean useRerank, int topK) {
        int fetchK = useRerank ? hybridTopK : topK;
        return Mono.fromCallable(() -> hybridSearchService.hybridSearch(query, null, fetchK, useSparseSearch))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(candidates -> {
                    if (useRerank) {
                        return rerankService.rerank(query, candidates, topK);
                    }
                    return Mono.just(candidates.stream().limit(topK).toList());
                });
    }

    // TODO:: 精确计算token消耗量
    public Mono<ChatStreamResponse> streamWithSources(String userInput, String conversationId, List<String> filterTags,
            String modelId) {
        log.info("Processing query: '{}', conversationId: {}, tags: {}, modelId: {}", userInput, conversationId,
                filterTags, modelId);

        long searchStart = System.currentTimeMillis();
        return Mono.fromCallable(() -> hybridSearchService.hybridSearch(userInput, filterTags, hybridTopK))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(candidates -> log.debug("Hybrid search returned {} candidates in {}ms",
                        candidates.size(), System.currentTimeMillis() - searchStart))
                .flatMap(candidates -> {
                    long rerankStart = System.currentTimeMillis();
                    return rerankService.rerank(userInput, candidates, rerankTopK)
                            .doOnNext(docs -> log.debug("Rerank returned {} docs in {}ms",
                                    docs.size(), System.currentTimeMillis() - rerankStart));
                })
                .map(docs -> {
                    // 3. Build Context with length limit to prevent LLM context overflow
                    StringBuilder contextBuilder = new StringBuilder();
                    for (int i = 0; i < docs.size(); i++) {
                        Document doc = docs.get(i);
                        String filename = (String) doc.getMetadata().getOrDefault("file_name", "Unknown Source");

                        String structuredEntry = String.format(
                                """
                                                【第%s页】(来源: %s)
                                                内容: %s
                                                ------------------------
                                                """,
                                i + 1, filename, doc.getText());

                        if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                            log.warn("Context limit reached, dropping remaining documents from rank {}", i);
                            break;
                        }
                        contextBuilder.append(structuredEntry);
                    }
                    String context = contextBuilder.toString();

                    // 4. Resolve the strategy and stream response
                    ChatModelStrategy strategy = strategyFactory.getStrategy(modelId);
                    ChatClient chatClient = strategy.getChatClient();

                    Flux<String> flux = chatClient.prompt()
                            .system(s -> s.text(strategy.getSystemPromptTemplate())
                                    .param("context", context))
                            .user(userInput)
                            .advisors(messageChatMemoryAdvisor)
                            .advisors(spec -> spec.param(CONVERSATION_ID, conversationId))
                            .stream()
                            .content();

                    return new ChatStreamResponse(flux, docs);
                });
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
        return evaluateQuery(question, groundTruth, config, baselinePromptResource, optimizedPromptResource, rerankTopK, modelId);
    }

    public Mono<EvaluationResultItem> evaluateQuery(
            String question,
            String groundTruth,
            EvaluationConfig config,
            Resource baselinePromptResource,
            Resource optimizedPromptResource,
            int topK,
            String modelId) {

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

                    ChatClient chatClient = strategyFactory.getStrategy(modelId).getChatClient();
                    String generatedAnswer = chatClient.prompt()
                            .system(s -> s.text(systemPromptText)
                                    .param("context", contextText)
                                    .param("question", question))
                            .user(question)
                            .call()
                            .content();

                    log.info("Evaluation query completed in {}ms. Length of answer: {}",
                            System.currentTimeMillis() - startTime, generatedAnswer.length());

                    return Mono.just(new EvaluationResultItem(
                            question,
                            groundTruth,
                            generatedAnswer,
                            contextNodes));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(reactor.util.retry.Retry.backoff(3, java.time.Duration.ofSeconds(2))
                        .filter(throwable -> {
                            // Can add specific sub-exceptions if needed, but for now retry on most runtime exceptions from APIs
                            return throwable instanceof RuntimeException; 
                        })
                        .doBeforeRetry(retrySignal -> log.warn("[{}] API error '{}', retrying... ({}/{})", 
                                modelId, retrySignal.failure().getMessage(), retrySignal.totalRetriesInARow() + 1, 3)))
                .onErrorResume(e -> {
                    log.error("[{}] API definitively failed after retries: {}", modelId, e.getMessage());
                    if (!"ollama".equals(modelId)) {
                        log.warn("=== 触发降级机制 === 切换至本地大模型 (ollama) 作兜底回复");
                        // Recursively call evaluateQuery but force the modelId to be 'ollama'
                        return evaluateQuery(question, groundTruth, config, baselinePromptResource, optimizedPromptResource, topK, "ollama");
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
}
