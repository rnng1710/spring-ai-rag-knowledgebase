package net.topikachu.rag.business.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import net.topikachu.rag.common.BaseEntity;

@Data
@TableName(value = "knowledge_document", autoResultMap = true)
public class Document extends BaseEntity {

    @TableField("DOC_UUID")
    private String docUuid;

    @TableField("FILE_NAME")
    private String fileName;

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

    @TableField("SPACE_CODE")
    private String spaceCode;

    @TableField("OWNER_DEPT_ID")
    private String ownerDeptId;

    @TableField(value = "ALLOWED_ROLES", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private java.util.List<String> allowedRoles;

    @TableField(value = "ALLOWED_DEPT_IDS", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private java.util.List<String> allowedDeptIds;

    @TableField("IS_PUBLIC")
    private Boolean isPublic;

    @TableField("ACL_VERSION")
    private Integer aclVersion;

    @TableField("ACL_REFRESH_STATUS")
    private String aclRefreshStatus;

    @TableField("ACL_REFRESH_ERROR")
    private String aclRefreshError;

    @TableField("ACL_REFRESH_TIME")
    private java.time.LocalDateTime aclRefreshTime;

    @TableField("ERROR_MESSAGE")
    private String errorMessage;

    @TableField("ERROR_STACK")
    private String errorStack;

    @TableField("RETRY_COUNT")
    private Integer retryCount;

    @TableField("OBJECT_KEY")
    private String objectKey;
}
