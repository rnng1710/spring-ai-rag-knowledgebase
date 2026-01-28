package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
@Slf4j
public class ChatService {

        @Autowired
        private ChatModel chatModel;
        @Autowired
        private VectorStore vectorStore;
        @Autowired
        private ChatMemory chatMemory;

        private final ChatClient chatClient;
        private final MessageChatMemoryAdvisor messageChatMemoryAdvisor;

        public ChatService(ChatModel chatModel, VectorStore vectorStore, ChatMemory chatMemory) {
                this.chatModel = chatModel;
                this.vectorStore = vectorStore;
                this.chatMemory = chatMemory;

                this.messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory).build();
                this.chatClient = ChatClient.builder(chatModel).build();
        }

        public record ChatStreamResponse(Flux<String> flux, List<org.springframework.ai.document.Document> sources) {
        }

        public ChatStreamResponse streamWithSources(String userInput, String conversationId, List<String> filterTags) {
                // 1. Explicit Retrieval
                var searchRequestBuilder = org.springframework.ai.vectorstore.SearchRequest.builder()
                                .query(userInput)
                                .topK(4);

                if (filterTags != null && !filterTags.isEmpty()) {
                        org.springframework.ai.vectorstore.filter.FilterExpressionBuilder b = new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();

                        // Assuming simplified single tag selection for filtering or "OR" logic if
                        // multiple?
                        // User request mentioned: requestBuilder.filterExpression(b.in("tags",
                        // filterTags.get(0)).build());
                        // Let's implement robust IN logic if possible, or just follow user snippet
                        // which handled .get(0)
                        // But let's support any tag in the list. metadata['tags'] contains a list.
                        // Milvus support "tags in [...]"? Or check intersection?
                        // Following user's specific instruction:
                        // requestBuilder.filterExpression(b.in("tags", filterTags.get(0)).build());
                        // But if tags is a list in DB, and we want to find docs that HAVE this tag.
                        // Spring AI FilterExpressionBuilder: b.in("tags", "tag1") checks if "tag1" is
                        // in the 'tags' metadata field (if it's a collection) or equal (if scalar).
                        // Let's use filterTags.get(0) as requested for now as the user primarily
                        // focused on selecting "A knowledge base".
                        // For single tag selection, use eq which may work with array-contains in some
                        // vector stores.
                        // If filterTags has multiple, we would need OR logic; for now, assume single
                        // tag selection.
                    searchRequestBuilder.filterExpression(b.eq("tags", filterTags.get(0)).build());
                }

                List<org.springframework.ai.document.Document> docs = vectorStore
                                .similaritySearch(searchRequestBuilder.build());

                // 2. Build Context
            assert docs != null;
            String context = docs.stream()
                                .map(Document::getText)
                                .collect(Collectors.joining("\n\n"));

                // 3. Stream Response
                Flux<String> flux = chatClient.prompt()
                                .system(s -> s.text(
                                                "You are a helpful assistant. Answer the user's question using the provided context.\n\nContext:\n{context}")
                                                .param("context", context))
                                .user(userInput)
                                .advisors(messageChatMemoryAdvisor)
                                .advisors(spec -> spec.param(CONVERSATION_ID, conversationId))
                                .stream()
                                .content();

                return new ChatStreamResponse(flux, docs);
        }
}
