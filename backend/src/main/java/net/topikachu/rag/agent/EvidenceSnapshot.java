package net.topikachu.rag.agent;

import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record EvidenceSnapshot(
        String id,
        String text,
        Map<String, Object> metadataSnapshot
) {

    public static EvidenceSnapshot fromDocument(Document document) {
        Objects.requireNonNull(document, "document must not be null");
        Object evidenceId = document.getMetadata().get("evidence_id");
        return new EvidenceSnapshot(
                evidenceId == null || evidenceId.toString().isBlank()
                        ? document.getId()
                        : evidenceId.toString(),
                document.getText(),
                deepImmutableCopy(document.getMetadata()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepImmutableCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copied = new LinkedHashMap<>();
        source.forEach((key, value) -> copied.put(key, deepCopyValue(value)));
        return Map.copyOf(copied);
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object value) {
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> copied = new LinkedHashMap<>();
            mapValue.forEach((key, nestedValue) -> copied.put(String.valueOf(key), deepCopyValue(nestedValue)));
            return Map.copyOf(copied);
        }
        if (value instanceof List<?> listValue) {
            List<Object> copied = new ArrayList<>(listValue.size());
            for (Object item : listValue) {
                copied.add(deepCopyValue(item));
            }
            return List.copyOf(copied);
        }
        return value;
    }
}
