package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
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

    public ChatStreamResponse streamWithSources(String userInput, String conversationId) {
        // 1. Explicit Retrieval
        List<org.springframework.ai.document.Document> docs = vectorStore.similaritySearch(
                org.springframework.ai.vectorstore.SearchRequest.builder()
                        .query(userInput)
                        .topK(4)
                        .build());

        // 2. Build Context
        String context = docs.stream()
                .map(d -> d.getText())
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
