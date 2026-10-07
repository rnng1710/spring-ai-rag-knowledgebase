package net.topikachu.rag.evaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_evaluation")
public class ChatEvaluationEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String conversationId;
    private String userId;
    private String question;
    private String answer;
    private String modelId;
    private String mode;

    private String contextSnippets;
    private String usedSources;
    @TableField("`reference`")
    private String reference;
    private LocalDateTime referenceUpdateDate;
    private String referenceUpdatedBy;

    private String traceId;
    private String rating;
    private String failureMode;

    @TableField("create_date")
    private LocalDateTime createDate;

    @TableField("update_date")
    private LocalDateTime updateDate;
}
