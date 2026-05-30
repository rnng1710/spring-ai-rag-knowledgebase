package net.topikachu.rag.autoevaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.autoevaluation.entity.ChatEvaluationAutoEntity;
import net.topikachu.rag.evaluation.entity.ChatEvaluationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ChatEvaluationAutoMapper extends BaseMapper<ChatEvaluationAutoEntity> {

    @Select("""
            <script>
            SELECT e.*
            FROM chat_evaluation e
            WHERE e.mode = 'rag'
              AND e.create_date &gt;= #{since}
              AND e.answer IS NOT NULL
              AND LENGTH(TRIM(e.answer)) &gt;= 20
              AND e.question IS NOT NULL
              AND LENGTH(TRIM(e.question)) &gt;= 5
              AND NOT EXISTS (
                  SELECT 1
                  FROM chat_evaluation_auto a
                  WHERE a.evaluation_id = e.id
                    AND a.reference_answer_hash = CASE
                        WHEN e.`reference` IS NULL OR TRIM(e.`reference`) = '' THEN 'NO_REFERENCE'
                        ELSE SHA2(e.`reference`, 256)
                    END
              )
            ORDER BY e.create_date ASC
            LIMIT #{limit}
            </script>
            """)
    List<ChatEvaluationEntity> selectSamples(
            @Param("since") LocalDateTime since,
            @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) AS total_evaluated,
                   AVG(faithfulness) AS avg_faithfulness,
                   AVG(answer_relevancy) AS avg_answer_relevancy,
                   AVG(context_precision) AS avg_context_precision,
                   AVG(context_recall) AS avg_context_recall,
                   AVG(answer_correctness) AS avg_answer_correctness,
                   AVG(answer_similarity) AS avg_answer_similarity
            FROM chat_evaluation_auto
            """)
    Map<String, Object> selectAutoSummary();

    @Select("""
            SELECT DATE(create_date) AS day,
                   AVG(faithfulness) AS faithfulness,
                   AVG(answer_relevancy) AS answer_relevancy,
                   AVG(context_precision) AS context_precision,
                   AVG(context_recall) AS context_recall,
                   AVG(answer_correctness) AS answer_correctness,
                   AVG(answer_similarity) AS answer_similarity
            FROM chat_evaluation_auto
            WHERE create_date >= DATE_SUB(CURRENT_DATE, INTERVAL 6 DAY)
            GROUP BY DATE(create_date)
            ORDER BY DATE(create_date)
            """)
    List<Map<String, Object>> selectAutoTrend();
}
