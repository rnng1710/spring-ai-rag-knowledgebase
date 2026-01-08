package net.topikachu.rag.business.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchUploadResponse {
    private int total;
    private int successCount;
    private int createdCount;
    private int existedCount;
    private int failedCount;

    private List<UploadItemResult> results;
}
