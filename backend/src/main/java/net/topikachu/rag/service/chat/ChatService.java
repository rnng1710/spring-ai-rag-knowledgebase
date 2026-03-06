package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategy;
import net.topikachu.rag.service.chat.strategy.ChatModelStrategyFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

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

        @Value("${rag.retrieval.rerank-topk:5}")
        private int rerankTopK;

        @Value("${rag.retrieval.max-context-chars:4000}")
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

        // TODO:: 精确计算token消耗量
        public ChatStreamResponse streamWithSources(String userInput, String conversationId, List<String> filterTags,
                        String modelId) {
                log.info("Processing query: '{}', conversationId: {}, tags: {}, modelId: {}", userInput, conversationId,
                                filterTags, modelId);

                // 1. Hybrid Search (Dense + Sparse with RRF fusion)
                long searchStart = System.currentTimeMillis();
                List<Document> candidates = hybridSearchService.hybridSearch(userInput, filterTags, hybridTopK);
                log.debug("Hybrid search returned {} candidates in {}ms",
                                candidates.size(), System.currentTimeMillis() - searchStart);

                // 2. Rerank (with circuit breaker)
                long rerankStart = System.currentTimeMillis();
                List<Document> docs = rerankService.rerank(userInput, candidates, rerankTopK);
                log.debug("Rerank returned {} docs in {}ms",
                                docs.size(), System.currentTimeMillis() - rerankStart);

                // 3. Build Context with length limit to prevent LLM context overflow
                StringBuilder contextBuilder = new StringBuilder();
                for (int i = 0; i < docs.size(); i++) {
                        Document doc = docs.get(i);
                        String filename = (String) doc.getMetadata().getOrDefault("file_name", "Unknown Source");

                        // Structured injection with source tags
                        String structuredEntry = String.format(
                                        """
                                                        【文档 %d】(来源: %s)
                                                        内容: %s
                                                        ------------------------
                                                        """, i + 1, filename, doc.getText());

                        if (contextBuilder.length() + structuredEntry.length() > maxContextChars) {
                                log.warn("Context limit reached, dropping remaining documents from rank {}", i);
                                break; // It is better to discard a complete document with a lower ranking than to
                                       // truncate the middle of the document
                        }
                        contextBuilder.append(structuredEntry);
                }
                String context = contextBuilder.toString();
                // log.info("Context built with {} chars (max: {}). Final Context sent to
                // LLM:\n================================\n{}\n================================",
                // context.length(), maxContextChars, context);

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
                // log.debug("Context built with {} chars", context);

                return new ChatStreamResponse(flux, docs);
        }

        /**
         * Non-streaming, strict evaluation method used by AblationStudyRunner.
         * Extracts ContextNodes with metadata and applies dynamic evaluation
         * configurations.
         */
        public net.topikachu.rag.evaluation.EvaluationResultItem evaluateQuery(
                        String question,
                        String groundTruth,
                        net.topikachu.rag.evaluation.EvaluationConfig config,
                        org.springframework.core.io.Resource baselinePromptResource,
                        org.springframework.core.io.Resource optimizedPromptResource) {

                long startTime = System.currentTimeMillis();

                // 1. Retrieval
                List<Document> candidates = hybridSearchService.hybridSearch(question, null, hybridTopK,
                                config.useSparseSearch());

                // 2. Reranking
                List<Document> docs;
                if (config.useRerank()) {
                        docs = rerankService.rerank(question, candidates, rerankTopK);
                } else {
                        docs = candidates.stream().limit(rerankTopK).toList();
                }

                // 3. Extract Contexts and build LLM input string
                List<net.topikachu.rag.evaluation.ContextNode> contextNodes = new java.util.ArrayList<>();
                StringBuilder contextTextBuilder = new StringBuilder();

                for (int i = 0; i < docs.size(); i++) {
                        Document doc = docs.get(i);
                        String docId = (String) doc.getMetadata().getOrDefault("doc_id", "Unknown ID");
                        String fileName = (String) doc.getMetadata().getOrDefault("file_name", "Unknown File");
                        Object scoreObj = doc.getMetadata().get("score");
                        Double score = (scoreObj instanceof Number) ? ((Number) scoreObj).doubleValue() : 0.0;

                        // Build the node for evaluation output (traceable)
                        contextNodes.add(new net.topikachu.rag.evaluation.ContextNode(doc.getText(), fileName, score));

                        // Build the clean string for LLM input (prevent hallucination/contamination)
                        String structuredEntry = String.format("【文档来源: %s】\n内容: %s\n------------------------\n",
                                        fileName, doc.getText());

                        if (contextTextBuilder.length() + structuredEntry.length() > maxContextChars) {
                                break;
                        }
                        contextTextBuilder.append(structuredEntry);
                }

                String contextText = contextTextBuilder.toString();

                // 4. Select Prompt Template
                String tempPromptText;
                try {
                        org.springframework.core.io.Resource promptResource = config.useOptimizedPrompt()
                                        ? optimizedPromptResource
                                        : baselinePromptResource;
                        tempPromptText = promptResource.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                        log.error("Failed to load prompt template, falling back to default.", e);
                        tempPromptText = "Context:\n{context}"; // Absolute fallback
                }
                final String systemPromptText = tempPromptText;

                // 5. Generate Answer (Blocking call for offline evaluation)
                ChatClient chatClient = strategyFactory.getStrategy("ollama").getChatClient();
                String generatedAnswer = chatClient.prompt()
                                .system(s -> s.text(systemPromptText).param("context", contextText))
                                .user(question)
                                .call()
                                .content();

                log.info("Evaluation query completed in {}ms. Length of answer: {}",
                                System.currentTimeMillis() - startTime, generatedAnswer.length());

                return new net.topikachu.rag.evaluation.EvaluationResultItem(
                                question,
                                groundTruth,
                                generatedAnswer,
                                contextNodes);
        }
}
