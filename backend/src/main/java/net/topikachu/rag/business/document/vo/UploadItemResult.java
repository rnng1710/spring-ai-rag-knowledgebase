package net.topikachu.rag.business.document.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadItemResult {
    private boolean success;   // 本文件是否成功处理
    private boolean created;   // success=true 时是否新建
    private String docUuid;
    private String fileName;
    private String status;
    private String fileHash;
    private String error;      // success=false 时的错误信息
}