package net.topikachu.rag.autoevaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.autoevaluation.entity.ChatEvaluationAutoRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ChatEvaluationAutoRunMapper extends BaseMapper<ChatEvaluationAutoRunEntity> {

    @Select("""
            SELECT COUNT(*)
            FROM chat_evaluation_auto_run
            WHERE status = 'RUNNING'
            """)
    long countRunning();

    @Update("""
            UPDATE chat_evaluation_auto_run
            SET total_samples = #{totalSamples}
            WHERE run_id = #{runId}
            """)
    int updateTotalSamples(
            @Param("runId") String runId,
            @Param("totalSamples") int totalSamples);

    @Update("""
            UPDATE chat_evaluation_auto_run
            SET status = #{status},
                success_count = #{successCount},
                failure_count = #{failureCount},
                avg_faithfulness = #{avgFaithfulness},
                avg_answer_relevancy = #{avgAnswerRelevancy},
                avg_context_precision = #{avgContextPrecision},
                avg_context_recall = #{avgContextRecall},
                avg_answer_correctness = #{avgAnswerCorrectness},
                avg_answer_similarity = #{avgAnswerSimilarity},
                completed_at = #{completedAt},
                error_message = #{errorMessage}
            WHERE run_id = #{runId}
            """)
    int updateCompletion(
            @Param("runId") String runId,
            @Param("status") String status,
            @Param("successCount") int successCount,
            @Param("failureCount") int failureCount,
            @Param("avgFaithfulness") BigDecimal avgFaithfulness,
            @Param("avgAnswerRelevancy") BigDecimal avgAnswerRelevancy,
            @Param("avgContextPrecision") BigDecimal avgContextPrecision,
            @Param("avgContextRecall") BigDecimal avgContextRecall,
            @Param("avgAnswerCorrectness") BigDecimal avgAnswerCorrectness,
            @Param("avgAnswerSimilarity") BigDecimal avgAnswerSimilarity,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE chat_evaluation_auto_run
            SET status = 'FAILED',
                completed_at = #{completedAt},
                error_message = #{errorMessage}
            WHERE status = 'RUNNING'
              AND started_at < #{threshold}
            """)
    int markStaleFailed(
            @Param("threshold") LocalDateTime threshold,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("errorMessage") String errorMessage);

    @Select("""
            SELECT AVG(faithfulness) AS avg_faithfulness,
                   AVG(answer_relevancy) AS avg_answer_relevancy,
                   AVG(context_precision) AS avg_context_precision,
                   AVG(context_recall) AS avg_context_recall,
                   AVG(answer_correctness) AS avg_answer_correctness,
                   AVG(answer_similarity) AS avg_answer_similarity
            FROM chat_evaluation_auto
            WHERE run_id = #{runId}
            """)
    java.util.Map<String, Object> selectRunAverages(@Param("runId") String runId);

    @Select("""
            SELECT *
            FROM chat_evaluation_auto_run
            ORDER BY started_at DESC
            """)
    List<ChatEvaluationAutoRunEntity> selectRunHistory();

    @Select("""
            SELECT *
            FROM chat_evaluation_auto_run
            ORDER BY started_at DESC
            LIMIT 1
            """)
    ChatEvaluationAutoRunEntity selectLastRun();
}
