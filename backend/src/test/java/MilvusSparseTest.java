import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.*;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import org.junit.jupiter.api.Test;

import java.util.*;

public class MilvusSparseTest {

    // 你的 Milvus 地址
    private static final String HOST = "192.168.193.128";
    private static final int PORT = 19530;

    @Test
    public void testSparseVectorFlow() {
        // 1. 连接 Milvus
        MilvusServiceClient milvusClient = new MilvusServiceClient(
                ConnectParam.newBuilder().withHost(HOST).withPort(PORT).build()
        );

        String collectionName = "test_hybrid_rag";

        // 清理旧数据（防止报错）
        milvusClient.dropCollection(DropCollectionParam.newBuilder().withCollectionName(collectionName).build());

        // 2. 定义 Schema
        FieldType idField = FieldType.newBuilder()
                .withName("id")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType denseField = FieldType.newBuilder()
                .withName("dense_vector")
                .withDataType(DataType.FloatVector)
                .withDimension(4)
                .build();

        FieldType sparseField = FieldType.newBuilder()
                .withName("sparse_vector")
                .withDataType(DataType.SparseFloatVector)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .addFieldType(idField)
                .addFieldType(denseField)
                .addFieldType(sparseField)
                .build();

        R<RpcStatus> createR = milvusClient.createCollection(createParam);
        System.out.println("建表结果: " + createR.getStatus());

        // 3. 准备数据 (使用 Gson)
        Gson gson = new Gson();
        SortedMap<Long, Float> sparseData = new TreeMap<>();
        sparseData.put(101L, 0.5f);
        sparseData.put(202L, 0.8f);

        List<JsonObject> rows = new ArrayList<>(); // 注意这里泛型变了
        JsonObject row = new JsonObject();

        // Gson 的写法和 Map 不一样：
        // 基础类型用 addProperty
        row.addProperty("id", 1L);
        // 复杂对象（List/Map）要转成 JsonTree 再 add
        row.add("dense_vector", gson.toJsonTree(Arrays.asList(0.1f, 0.2f, 0.3f, 0.4f)));
        row.add("sparse_vector", gson.toJsonTree(sparseData));

        rows.add(row);

        // 4. 插入数据
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(collectionName)
                .withRows(rows) // 现在这里的类型匹配了
                .build();

        R<MutationResult> insertR = milvusClient.insert(insertParam);
        System.out.println("插入结果: " + insertR.getStatus());

        // 4.1 为稠密向量 (dense_vector) 建索引
// 使用 AUTOINDEX 让 Milvus 自动选择最适合的索引类型 (或者用 FLAT)
        R<RpcStatus> indexDenseR = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("dense_vector")
                        .withIndexType(IndexType.AUTOINDEX) // 或者 IndexType.FLAT
                        .withMetricType(MetricType.COSINE)  // 必须与 Search 时的 MetricType 一致
                        .build()
        );
        System.out.println("稠密索引结果: " + indexDenseR.getStatus());

// 4.2 为稀疏向量 (sparse_vector) 建索引
// 稀疏向量必须使用 SPARSE_INVERTED_INDEX
        R<RpcStatus> indexSparseR = milvusClient.createIndex(
                CreateIndexParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFieldName("sparse_vector")
                        .withIndexType(IndexType.SPARSE_INVERTED_INDEX) // <--- 关键！
                        .withMetricType(MetricType.IP) // 稀疏向量通常用内积 (Inner Product)
                        .build()
        );
        System.out.println("稀疏索引结果: " + indexSparseR.getStatus());

        R<RpcStatus> loadR = milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withCollectionName(collectionName)
                        .build()
        );
        System.out.println("加载结果: " + loadR.getStatus());

        // 5. 稀疏检索
        // 构造查询向量
        SortedMap<Long, Float> querySparse = new TreeMap<>();
        querySparse.put(202L, 0.8f);

        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collectionName)
                .withMetricType(MetricType.IP)
                .withTopK(10)
                // 这里的 Collections.singletonList 里面放 Map 是可以的，SDK 会处理
                .withVectors(Collections.singletonList(querySparse))
                .withVectorFieldName("sparse_vector")
                .build();

        R<SearchResults> searchR = milvusClient.search(searchParam);
        System.out.println("搜索结果: " + searchR.getData());

        milvusClient.close();
    }
}