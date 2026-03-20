package net.topikachu.rag.ai.memory;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Component
public class BlockingChatMemoryService {

    private final ChatMemory chatMemory;

    public BlockingChatMemoryService(ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
    }

    public Mono<Void> add(String conversationId, List<Message> messages) {
        return Mono.fromRunnable(() -> chatMemory.add(conversationId, messages))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
