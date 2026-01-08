package net.topikachu.rag.business.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadResult {
    /** Is newly built：true=newly，false=repeated hits（Idempotent return the existing record） */
    private boolean created;

    private String docUuid;
    private String fileName;
    private String status;
    private String fileHash;
}