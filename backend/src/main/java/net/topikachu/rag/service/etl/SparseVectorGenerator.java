package net.topikachu.rag.service.etl;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.zip.CRC32;

@Component
public class SparseVectorGenerator {

    // 将文本转换为 Milvus 稀疏向量格式 (Map<TokenHash, Weight>)
    public SortedMap<Long, Float> generate(String text) {
        SortedMap<Long, Float> sparseVector = new TreeMap<>();
        if (text == null || text.isEmpty())
            return sparseVector;

        // 1. 简单分词 (可替换为 HanLP 或 Jieba 以获得更精准中文分词)
        // 这里用正则简单处理：保留中文、英文、数字
        String[] tokens = text.toLowerCase().split("[^a-zA-Z0-9\\u4e00-\\u9fa5]+");

        // 2. 计算词频 (TF)
        Map<String, Integer> tfMap = new HashMap<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                tfMap.put(token, tfMap.getOrDefault(token, 0) + 1);
            }
        }

        // 3. 生成稀疏向量 (Hash -> Weight)
        for (Map.Entry<String, Integer> entry : tfMap.entrySet()) {
            long hash = hashToken(entry.getKey());
            // 简单的权重策略，也可以引入 IDF
            float weight = (float) Math.log(1 + entry.getValue());
            sparseVector.put(hash, weight);
        }
        return sparseVector;
    }

    // 使用 CRC32 将 Token 映射为 Long
    private long hashToken(String token) {
        CRC32 crc = new CRC32();
        crc.update(token.getBytes());
        return Math.abs(crc.getValue()); // Milvus Sparse Vector indices must be positive
    }
}
