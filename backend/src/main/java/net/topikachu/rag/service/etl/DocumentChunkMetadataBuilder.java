package net.topikachu.rag.service.etl;

import net.topikachu.rag.business.document.access.KnowledgeAccessPolicy;
import net.topikachu.rag.business.document.entity.Document;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DocumentChunkMetadataBuilder {

    private final KnowledgeAccessPolicy accessPolicy;

    public DocumentChunkMetadataBuilder(KnowledgeAccessPolicy accessPolicy) {
        this.accessPolicy = accessPolicy;
    }

    public Map<String, Object> build(Document storedDocument,
                                     String docUuid,
                                     String fileName,
                                     List<String> effectiveTags,
                                     Map<String, Object> currentChunkMetadata,
                                     Integer aclVersion) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("doc_uuid", docUuid);
        metadata.put("file_name", fileName);
        metadata.putAll(accessPolicy.buildChunkAccessMetadata(storedDocument, effectiveTags, aclVersion));

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
}
