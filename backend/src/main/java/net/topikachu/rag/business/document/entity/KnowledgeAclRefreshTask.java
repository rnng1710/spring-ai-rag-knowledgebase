package net.topikachu.rag.business.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import net.topikachu.rag.common.BaseEntity;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_acl_refresh_task")
public class KnowledgeAclRefreshTask extends BaseEntity {

    @TableField("doc_uuid")
    private String docUuid;

    @TableField("target_acl_version")
    private Integer targetAclVersion;

    @TableField("status")
    private String status;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("last_error")
    private String lastError;

    @TableField("next_retry_time")
    private LocalDateTime nextRetryTime;
}
