package net.topikachu.rag.business.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import net.topikachu.rag.common.BaseEntity;

import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "etl_job", autoResultMap = true)
public class EtlJob extends BaseEntity {

    /**
     * 任务唯一ID
     */
    @TableField("JOB_UUID")
    private String jobUuid;

    /**
     * 文档UUID
     */
    @TableField("DOC_UUID")
    private String docUuid;

    @TableField("JOB_TYPE")
    private String jobType;

    /**
     * PENDING/RUNING/SUCCESS/FAILED/CANCELED
     */
    @TableField("STATUS")
    private String status;

    /**
     * 源文件路径
     */
    @TableField("FILE_PATH")
    private String filePath;

    /**
     * 文件名
     */
    @TableField("FILE_NAME")
    private String fileName;

    /**
     * 文档标签
     */
    @TableField(value = "TAGS", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<String> tags;

    /**
     * 重试次数
     */
    @TableField("RETRY_COUNT")
    private Integer retryCount;

    /**
     * 最大重试次数
     */
    @TableField("MAX_RETRY_COUNT")
    private Integer maxRetryCount;

    /**
     * 下次重试时间
     */
    @TableField("NEXT_RETRY_TIME")
    private LocalDateTime nextRetryTime;

    /**
     * 哪个worker抢到了任务
     */
    @TableField("LOCKED_BY")
    private String lockedBy;

    /**
     * 锁过期时间
     */
    @TableField("LOCKED_UNTIL")
    private LocalDateTime lockedUntil;

    /**
     * 任务开始时间
     */
    @TableField("STARTED_AT")
    private LocalDateTime startedAt;

    /**
     * 任务结束时间
     */
    @TableField("FINISHED_AT")
    private LocalDateTime finishedAt;

    /**
     * 最新错误信息
     */
    @TableField("LAST_ERROR")
    private String lastError;

    /**
     * 错误堆栈
     */
    @TableField("ERROR_STACK")
    private String errorStack;

    /**
     * 活跃任务唯一键
     */
    @TableField("ACTIVE_KEY")
    private String activeKey;

    /**
     * 文件密钥
     */
    @TableField("OBJECT_KEY")
    private String objectKey;
}
