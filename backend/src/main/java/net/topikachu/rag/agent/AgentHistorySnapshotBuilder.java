package net.topikachu.rag.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class AgentHistorySnapshotBuilder {

    private final ChatMemory chatMemory;
    private final int maxMessages;

    public AgentHistorySnapshotBuilder(ChatMemory chatMemory,
                                       @Value("${rag.agent.history.max-messages:8}") int maxMessages) {
        this.chatMemory = chatMemory;
        this.maxMessages = maxMessages;
    }

    public List<Message> build(String conversationId) {
        List<Message> history = chatMemory.get(conversationId);
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<Message> filtered = history.stream()
                .filter(message -> message instanceof UserMessage || message instanceof AssistantMessage)
                .toList();
        int fromIndex = Math.max(0, filtered.size() - maxMessages);
        return new ArrayList<>(filtered.subList(fromIndex, filtered.size()));
    }
}
