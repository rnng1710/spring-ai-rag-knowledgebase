package net.topikachu.rag.chat.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.topikachu.rag.chat.history.mapper.ChatMemorySnapshotMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ChatMemoryConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(TestConfiguration.class);

	@Test
	void registersOnlyMySqlRedisChatMemoryRepository() {
		contextRunner.run(context -> {
			assertThat(context.getBeansOfType(ChatMemoryRepository.class))
					.hasSize(1);
			assertThat(context.getBean(ChatMemoryRepository.class))
					.isInstanceOf(MySqlRedisChatMemoryRepository.class);
		});
	}

	@Test
	void registersJsonChatMemoryRepositoryWhenConfigured() {
		contextRunner
				.withPropertyValues("rag.chat.memory.serializer=json")
				.run(context -> {
					assertThat(context.getBeansOfType(ChatMemoryRepository.class))
							.hasSize(1);
					assertThat(context.getBean(ChatMemoryRepository.class))
							.isInstanceOf(JsonMySqlRedisChatMemoryRepository.class);
				});
	}

	@Configuration(proxyBeanMethods = false)
	@Import({
			ChatMemoryConfiguration.class,
			MySqlRedisChatMemoryRepository.class,
			JsonMySqlRedisChatMemoryRepository.class,
			ChatMessageSerializer.class,
			JsonChatMessageSerializer.class
	})
	static class TestConfiguration {

		@Bean
		RedisConnectionFactory redisConnectionFactory() {
			return mock(RedisConnectionFactory.class);
		}

		@Bean
		ChatMemorySnapshotMapper chatMemorySnapshotMapper() {
			return mock(ChatMemorySnapshotMapper.class);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
