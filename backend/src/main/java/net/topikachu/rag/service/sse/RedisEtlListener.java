package net.topikachu.rag.service.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.event.EtlStatusMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@Slf4j
public class RedisEtlListener implements MessageListener {

    @Autowired
    private SseService sseService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            EtlStatusMessage msg = objectMapper.readValue(message.getBody(), EtlStatusMessage.class);
            log.debug("Received Redis ETL message for user={}, doc={}", msg.getUserId(), msg.getDocUuid());
            sseService.broadcastLocal(msg);
        } catch (IOException e) {
            log.error("Failed to parse Redis ETL message", e);
        }
    }
}
