package net.topikachu.rag.service.etl;

import net.topikachu.rag.business.document.entity.KnowledgeParentBlock;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

public final class ChunkUtils {

    private ChunkUtils() {
    }

    public static String buildEvidenceId(String docUuid, Document doc, int chunkIndex) {
        String normalizedText = doc.getText() == null ? "" : doc.getText().replaceAll("\\s+", " ").trim();
        return docUuid + ":child:" + chunkIndex + ":" + sha256Short(normalizedText);
    }

    public static String sha256Short(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            // 取 SHA-256 hex 前 16 字符（64 位），在百万级块规模下碰撞概率可忽略
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build evidence id", e);
        }
    }

    public static String buildParentBlockId(String docUuid, String text, int parentIndex) {
        String normalizedText = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return docUuid + ":parent:" + parentIndex + ":" + sha256Short(normalizedText);
    }

    public static KnowledgeParentBlock toKnowledgeParentBlock(Document parentDocument) {
        Map<String, Object> metadata = parentDocument.getMetadata();
        KnowledgeParentBlock block = new KnowledgeParentBlock();
        block.setId(UUID.randomUUID().toString().replace("-", ""));
        block.setParentBlockId(stringValue(metadata.get("parent_block_id")));
        block.setDocUuid(stringValue(metadata.get("doc_uuid")));
        block.setParentIndex(parseInteger(metadata.get("parent_index")));
        block.setContent(parentDocument.getText());
        block.setFileName(stringValue(metadata.get("file_name")));
        block.setPageStart(parseInteger(metadata.get("page_start")));
        block.setPageEnd(parseInteger(metadata.get("page_end")));
        block.setSpaceCode(stringValue(metadata.get("space_code")));
        block.setTags(toStringList(metadata.get("tags")));
        block.setAclVersion(parseInteger(metadata.get("acl_version")));
        block.setChunkSchemaVersion(KnowledgeParentBlockService.CHUNK_SCHEMA_VERSION);
        block.setCreateDate(LocalDateTime.now());
        block.setUpdateDate(LocalDateTime.now());
        return block;
    }

    public static List<String> splitByFixedWindow(String text, int size, int overlap) {
        TextSanitizer.SanitizationResult sanitized = TextSanitizer.sanitize(text);
        if (sanitized.isEffectivelyEmpty()) {
            return List.of();
        }
        String value = sanitized.text();
        int safeSize = Math.max(1, size);
        // overlap 必须 < size，否则滑动窗口无法向前推进，造成死循环
        int safeOverlap = Math.max(0, Math.min(overlap, safeSize - 1));
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < value.length()) {
            int end = Math.min(value.length(), start + safeSize);
            String piece = value.substring(start, end).trim();
            if (!piece.isBlank()) {
                pieces.add(piece);
            }
            if (end >= value.length()) {
                break;
            }
            start = end - safeOverlap;
        }
        return pieces;
    }

    public static Integer parseInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    public static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Objects::nonNull)
                    .map(Object::toString)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    public static void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    public record ParentChildDocuments(
            List<KnowledgeParentBlock> parentBlocks,
            List<Document> childDocuments
    ) {
    }
}
