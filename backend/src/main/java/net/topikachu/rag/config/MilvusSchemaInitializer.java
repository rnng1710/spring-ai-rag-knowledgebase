package net.topikachu.rag.config;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.*;
import io.milvus.v2.service.collection.response.DescribeCollectionResp;
import io.milvus.v2.service.index.request.CreateIndexReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Initializes Milvus collection with hybrid search schema (dense + sparse
 * vectors).
 * Checks if collection has sparse_vector field before recreation.
 * Set spring.ai.vectorstore.milvus.initialize-schema=false to use this
 * initializer.
 */
@Component
@Order(1) // Run early
@Slf4j
public class MilvusSchemaInitializer implements CommandLineRunner {

        @Value("${spring.ai.vectorstore.milvus.client.host:localhost}")
        private String milvusHost;

        @Value("${spring.ai.vectorstore.milvus.client.port:19530}")
        private int milvusPort;

        @Value("${spring.ai.vectorstore.milvus.collection-name:vector_store}")
        private String collectionName;

        @Value("${spring.ai.vectorstore.milvus.embedding-dimension:768}")
        private int embeddingDimension;

        @Value("${rag.milvus.force-rebuild:false}")
        private boolean forceRebuild;

        @Override
        public void run(String... args) throws Exception {
                log.info("=== Milvus Schema Initializer Starting ===");
                log.info("Host: {}:{}, Collection: {}", milvusHost, milvusPort, collectionName);

                ConnectConfig config = ConnectConfig.builder()
                                .uri("http://" + milvusHost + ":" + milvusPort)
                                .build();

                MilvusClientV2 client = new MilvusClientV2(config);
                try {
                        boolean exists = client.hasCollection(
                                        HasCollectionReq.builder().collectionName(collectionName).build());

                        if (exists) {
                                // Check if collection has sparse_vector field
                                boolean hasSparseVector = checkHasSparseVector(client);

                                if (hasSparseVector && !forceRebuild) {
                                        log.info("Collection '{}' already has sparse_vector field. Skipping recreation.",
                                                        collectionName);
                                        return;
                                }

                                log.warn("Collection '{}' needs rebuild (hasSparseVector={}, forceRebuild={}). Dropping...",
                                                collectionName, hasSparseVector, forceRebuild);
                                client.dropCollection(
                                                DropCollectionReq.builder().collectionName(collectionName).build());
                                log.info("Collection dropped.");
                        }

                        createHybridCollection(client);

                        log.info("=== Milvus Schema Initialization Complete ===");
                } finally {
                        client.close();
                }
        }

        private boolean checkHasSparseVector(MilvusClientV2 client) {
                try {
                        DescribeCollectionResp resp = client.describeCollection(
                                        DescribeCollectionReq.builder().collectionName(collectionName).build());

                        return resp.getFieldNames().contains("sparse_vector");
                } catch (Exception e) {
                        log.warn("Failed to describe collection: {}", e.getMessage());
                        return false;
                }
        }

        private void createHybridCollection(MilvusClientV2 client) {
                // Create schema matching Spring AI + sparse vector
                CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();

                // doc_id: matches Spring AI default
                schema.addField(AddFieldReq.builder()
                                .fieldName("doc_id")
                                .dataType(DataType.VarChar)
                                .maxLength(36)
                                .isPrimaryKey(true)
                                .autoID(false)
                                .build());

                schema.addField(AddFieldReq.builder()
                                .fieldName("content")
                                .dataType(DataType.VarChar)
                                .maxLength(65535)
                                .build());

                schema.addField(AddFieldReq.builder()
                                .fieldName("metadata")
                                .dataType(DataType.JSON)
                                .build());

                // Dense vector field: matches Spring AI "embedding"
                schema.addField(AddFieldReq.builder()
                                .fieldName("embedding")
                                .dataType(DataType.FloatVector)
                                .dimension(embeddingDimension)
                                .build());

                // Sparse vector field (NEW for hybrid search)
                schema.addField(AddFieldReq.builder()
                                .fieldName("sparse_vector")
                                .dataType(DataType.SparseFloatVector)
                                .build());

                client.createCollection(CreateCollectionReq.builder()
                                .collectionName(collectionName)
                                .collectionSchema(schema)
                                .build());
                log.info("Collection '{}' created with hybrid schema.", collectionName);

                // Create indexes
                IndexParam denseIndex = IndexParam.builder()
                                .fieldName("embedding")
                                .indexType(IndexParam.IndexType.IVF_FLAT)
                                .metricType(IndexParam.MetricType.COSINE)
                                .extraParams(java.util.Map.of("nlist", 128))
                                .build();

                IndexParam sparseIndex = IndexParam.builder()
                                .fieldName("sparse_vector")
                                .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                                .metricType(IndexParam.MetricType.IP)
                                .build();

                client.createIndex(CreateIndexReq.builder()
                                .collectionName(collectionName)
                                .indexParams(List.of(denseIndex, sparseIndex))
                                .build());
                log.info("Indexes created for dense and sparse vectors.");

                client.loadCollection(
                                LoadCollectionReq.builder().collectionName(collectionName).build());
                log.info("Collection loaded into memory.");
        }
}
