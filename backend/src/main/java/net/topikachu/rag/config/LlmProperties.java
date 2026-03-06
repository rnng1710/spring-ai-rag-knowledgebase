package net.topikachu.rag.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "spring.ai.openai.deepseek")
public class LlmProperties {

    @Value("${DEEPSEEK_API_KEY}")
    private String apiKey;
    private String baseUrl = "https://api.deepseek.com";
    private Chat chat = new Chat();

    @Data
    public static class Chat {
        private Options options = new Options();
    }

    @Data
    public static class Options {
        private String model = "deepseek-chat";
    }
}
