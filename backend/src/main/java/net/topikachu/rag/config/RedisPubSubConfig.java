package net.topikachu.rag.config;

import net.topikachu.rag.service.sse.RedisEtlListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisPubSubConfig {

    public static final String TOPIC_ETL_STATUS = "topic:etl-status";

    @Bean
    RedisMessageListenerContainer container(RedisConnectionFactory connectionFactory,
            MessageListenerAdapter listenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listenerAdapter, new PatternTopic(TOPIC_ETL_STATUS));
        return container;
    }

    @Bean
    MessageListenerAdapter listenerAdapter(RedisEtlListener receiver) {
        // MessageListenerAdapter uses reflection to call "handleMessage" by default,
        // or we can implement MessageListener interface in RedisEtlListener.
        // Here we assume RedisEtlListener implements MessageListener.
        return new MessageListenerAdapter(receiver, "onMessage");
    }
}
