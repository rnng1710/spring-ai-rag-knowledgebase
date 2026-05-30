package net.topikachu.rag.autoevaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("chat_evaluation_auto")
public class ChatEvaluationAutoEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String evaluationId;
    private String runId;
    private BigDecimal faithfulness;
    private BigDecimal answerRelevancy;
    private BigDecimal contextPrecision;
    private BigDecimal contextRecall;
    private BigDecimal answerCorrectness;
    private BigDecimal answerSimilarity;
    private String referenceAnswerHash;

    @TableField("create_date")
    private LocalDateTime createDate;
}
