package net.topikachu.rag.service.etl;

import net.topikachu.rag.business.document.entity.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentChunkMetadataBuilder {

    public Map<String, Object> build(Document storedDocument,
                                     String docUuid,
                                     String fileName,
                                     List<String> effectiveTags,
                                     Map<String, Object> currentChunkMetadata,
                                     Integer aclVersion) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_uuid", docUuid);
        metadata.put("file_name", fileName);
        metadata.put("space_code", resolveSpaceCode(storedDocument));
        if (storedDocument != null && StringUtils.hasText(storedDocument.getOwnerDeptId())) {
            metadata.put("owner_dept_id", storedDocument.getOwnerDeptId().trim());
        }
        metadata.put("allowed_roles", storedDocument == null || storedDocument.getAllowedRoles() == null
                ? List.of()
                : List.copyOf(storedDocument.getAllowedRoles()));
        metadata.put("allowed_dept_ids", storedDocument == null || storedDocument.getAllowedDeptIds() == null
                ? List.of()
                : List.copyOf(storedDocument.getAllowedDeptIds()));
        // storedDocument 为 null 时（ETL 尚未关联到持久化记录）默认视为公开，后续可通过 ACL 刷新修正
        metadata.put("is_public", storedDocument == null || Boolean.TRUE.equals(storedDocument.getIsPublic()));
        metadata.put("acl_version", aclVersion == null ? 1 : aclVersion);

        if (effectiveTags != null && !effectiveTags.isEmpty()) {
            metadata.put("tags", List.copyOf(effectiveTags));
        }

        copyIfPresent(currentChunkMetadata, metadata, "page_number");
        copyIfPresent(currentChunkMetadata, metadata, "page");
        copyIfPresent(currentChunkMetadata, metadata, "page_start");
        copyIfPresent(currentChunkMetadata, metadata, "page_end");
        copyIfPresent(currentChunkMetadata, metadata, "parent_block_id");
        copyIfPresent(currentChunkMetadata, metadata, "parent_index");
        copyIfPresent(currentChunkMetadata, metadata, "child_index");
        copyIfPresent(currentChunkMetadata, metadata, "evidence_id");
        copyIfPresent(currentChunkMetadata, metadata, "chunk_schema_version");
        copyIfPresent(currentChunkMetadata, metadata, "source_location");
        return metadata;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source != null && source.get(key) != null) {
            target.put(key, source.get(key));
        }
    }

    // storedDocument 为 null 或未指定 spaceCode 时默认归入 "public" 空间，保证向量数据始终可分空间检索
    private String resolveSpaceCode(Document storedDocument) {
        if (storedDocument == null || !StringUtils.hasText(storedDocument.getSpaceCode())) {
            return "public";
        }
        return storedDocument.getSpaceCode().trim();
    }
}
