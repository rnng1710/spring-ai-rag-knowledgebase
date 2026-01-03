package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
@Slf4j
public class ChatService {


	MessageChatMemoryAdvisor messageChatMemoryAdvisor;
	RetrievalAugmentationAdvisor retrievalAugmentationAdvisor;
	ChatClient chatClient;
	private static final PromptTemplate COMPRESSION_PROMPT_TEMPLATE = new PromptTemplate("""
			Given the following conversation history and a follow-up query, your task is to synthesize
			a concise, standalone query that incorporates the context from the history.
			Ensure the standalone query is clear, specific, and maintains the user's intent.
			Return the standalone query as the response only without any other irrelevant content.
			Conversation history:
			{history}
			
			Follow-up query:
			{query}
			
			Standalone query:
			""");


	@Autowired
	public void ChatService(ChatModel chatModel, VectorStore vectorStore, ChatMemory chatMemory) {
		messageChatMemoryAdvisor = MessageChatMemoryAdvisor.builder(chatMemory)
				.build();
		retrievalAugmentationAdvisor = RetrievalAugmentationAdvisor.builder()
				.queryTransformers(
						CompressionQueryTransformer.builder()
								.chatClientBuilder(ChatClient.builder(chatModel))
								.promptTemplate(COMPRESSION_PROMPT_TEMPLATE)
								.build()
				)
				.documentRetriever(VectorStoreDocumentRetriever.builder()
						.vectorStore(vectorStore)
						.build())
				.build();
		chatClient = ChatClient.builder(chatModel)
				.build();
	}

	public ChatClient.ChatClientRequestSpec input(String userInput, String conversationId) {
		return chatClient.prompt()
				.advisors(
						messageChatMemoryAdvisor,
						retrievalAugmentationAdvisor
				)
				.advisors(spec -> spec.param(CONVERSATION_ID, conversationId))
				.user(userInput);
	}

	public Flux<String> stream(String userInput, String conversationId) {
		return input(userInput, conversationId)
				.stream().content();
	}

	public ChatResponse call(String userInput, String conversationId) {
		return input(userInput, conversationId)
				.call().chatResponse();
	}


}
