package net.topikachu.rag.business.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import net.topikachu.rag.common.BaseEntity;

@Data
@TableName(value = "knowledge_document", autoResultMap = true)
public class Document extends BaseEntity {

    /**
     * file UUID
     */
    @TableField("DOC_UUID")
    private String docUuid;

    /**
     * file name
     */
    @TableField("FILE_NAME")
    private String fileName;

    /**
     * file name
     */
    @TableField("STATUS")
    private String status;

    @TableField("FILE_HASH")
    private String fileHash;

    /**
     * 标签列表 (JSON)
     * DB: JSON Array String
     * Java: List<String>
     */
    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private java.util.List<String> tags;

    /**
     * User-friendly error message for display
     */
    @TableField("error_message")
    private String errorMessage;

    /**
     * Technical error stack trace for debugging
     */
    @TableField("error_stack")
    private String errorStack;

    /**
     * Retry count to prevent infinite retry loops
     */
    @TableField("retry_count")
    private Integer retryCount;
}
