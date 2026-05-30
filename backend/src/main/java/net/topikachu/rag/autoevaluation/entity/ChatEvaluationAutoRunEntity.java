package net.topikachu.rag.autoevaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("chat_evaluation_auto_run")
public class ChatEvaluationAutoRunEntity {

    @TableId(value = "run_id", type = IdType.INPUT)
    private String runId;

    private String status;
    private int totalSamples;
    private int successCount;
    private int failureCount;
    private BigDecimal avgFaithfulness;
    private BigDecimal avgAnswerRelevancy;
    private BigDecimal avgContextPrecision;
    private BigDecimal avgContextRecall;
    private BigDecimal avgAnswerCorrectness;
    private BigDecimal avgAnswerSimilarity;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
}
