package net.topikachu.rag.service.etl;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration(proxyBeanMethods = false)

public class EtlConfiguration {
	@Bean
	TextSplitter textSplitter() {
		// [修改后] Chunk: 400, Overlap: 80。适配 Reranker (max_length=512) 限制。
		return new Langchain4jSplitterAdapter(200, 80);
	}

	@Bean
	@Profile("single-document-batching")
	BatchingStrategy singleDocumentBatchingStrategy() {
		return documents -> documents.stream().map(List::of).toList();
	}

}
