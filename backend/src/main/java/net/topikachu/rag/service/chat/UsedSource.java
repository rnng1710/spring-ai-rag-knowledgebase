package net.topikachu.rag.service.chat;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record UsedSource(
        @JsonAlias("evidence_id")
        String evidenceId,
        @JsonAlias("doc_uuid")
        String docUuid,
        @JsonAlias("file_name")
        String fileName,
        @JsonAlias("page_number")
        Object pageNumber,
        @JsonAlias({"file_type", "mime_type", "mimeType"})
        String fileType
) {
}
