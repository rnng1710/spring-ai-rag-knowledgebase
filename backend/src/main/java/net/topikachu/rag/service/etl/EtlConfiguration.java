package net.topikachu.rag.service.etl;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration(proxyBeanMethods = false)

public class EtlConfiguration {
	@Bean
	// 父块切分为子块的分片器：chunk=200 字，overlap=80 字
	TextSplitter textSplitter(
			@Value("${rag.retrieval.child-chunk-size:200}") int childChunkSize,
			@Value("${rag.retrieval.child-chunk-overlap:80}") int childChunkOverlap) {
		return new Langchain4jSplitterAdapter(childChunkSize, childChunkOverlap);
	}

	// 开启后每个文档独立批处理，适用于调试或逐文档事务场景；默认关闭以利用批量向量化提升吞吐
	@Bean
	@Profile("single-document-batching")
	BatchingStrategy singleDocumentBatchingStrategy() {
		return documents -> documents.stream().map(List::of).toList();
	}

}
