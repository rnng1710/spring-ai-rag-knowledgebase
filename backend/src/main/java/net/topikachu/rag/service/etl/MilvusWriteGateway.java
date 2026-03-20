package net.topikachu.rag.service.etl;

import com.google.gson.JsonObject;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.response.InsertResp;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@Component
@Slf4j
public class MilvusWriteGateway {

    @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
    private String milvusHost;

    @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
    private int milvusPort;

    @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
    private String collectionName;

    private MilvusClientV2 milvusClient;

    @PostConstruct
    public void init() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + milvusHost + ":" + milvusPort)
                .build();
        this.milvusClient = new MilvusClientV2(config);
        log.info("MilvusWriteGateway initialized with Milvus at {}:{}", milvusHost, milvusPort);
    }

    @PreDestroy
    public void cleanup() {
        if (milvusClient != null) {
            milvusClient.close();
        }
    }

    public Mono<InsertResp> insert(List<JsonObject> rows) {
        return Mono.fromCallable(() -> milvusClient.insert(InsertReq.builder()
                        .collectionName(collectionName)
                        .data(rows)
                        .build()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Void> deleteByDocUuid(String docUuid) {
        return Mono.fromRunnable(() -> milvusClient.delete(DeleteReq.builder()
                        .collectionName(collectionName)
                        .filter("metadata[\"doc_uuid\"] == \"" + docUuid + "\"")
                        .build()))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }
}
