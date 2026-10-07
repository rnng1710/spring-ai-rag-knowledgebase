package net.topikachu.rag.evaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.evaluation.entity.ChatEvaluationEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface ChatEvaluationMapper extends BaseMapper<ChatEvaluationEntity> {

    @Select("""
            <script>
            SELECT COUNT(*) AS total,
                   SUM(CASE WHEN rating = 'positive' THEN 1 ELSE 0 END) AS positive,
                   SUM(CASE WHEN rating = 'negative' THEN 1 ELSE 0 END) AS negative,
                   SUM(CASE WHEN rating = 'negative' AND (failure_mode IS NULL OR failure_mode = '') THEN 1 ELSE 0 END) AS pending
            FROM chat_evaluation
            <where>
                <if test="modelId != null and modelId != ''">AND model_id = #{modelId}</if>
                <if test="rating != null and rating != ''">AND rating = #{rating}</if>
                <if test="failureMode != null and failureMode != ''">AND failure_mode = #{failureMode}</if>
                <if test="startDateTime != null">AND create_date &gt;= #{startDateTime}</if>
                <if test="endDateTime != null">AND create_date &lt; #{endDateTime}</if>
            </where>
            </script>
            """)
    Map<String, Object> selectStats(
            @Param("modelId") String modelId,
            @Param("rating") String rating,
            @Param("failureMode") String failureMode,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime);

    @Select("""
            <script>
            SELECT failure_mode, COUNT(*) AS count
            FROM chat_evaluation
            <where>
                failure_mode IS NOT NULL AND failure_mode != ''
                <if test="modelId != null and modelId != ''">AND model_id = #{modelId}</if>
                <if test="rating != null and rating != ''">AND rating = #{rating}</if>
                <if test="failureMode != null and failureMode != ''">AND failure_mode = #{failureMode}</if>
                <if test="startDateTime != null">AND create_date &gt;= #{startDateTime}</if>
                <if test="endDateTime != null">AND create_date &lt; #{endDateTime}</if>
            </where>
            GROUP BY failure_mode
            ORDER BY count DESC
            LIMIT 1
            </script>
            """)
    Map<String, Object> selectTopFailureMode(
            @Param("modelId") String modelId,
            @Param("rating") String rating,
            @Param("failureMode") String failureMode,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime);

    @Select("""
            <script>
            SELECT DATE(create_date) AS day,
                   CASE
                       WHEN SUM(CASE WHEN rating IN ('positive', 'negative') THEN 1 ELSE 0 END) = 0 THEN 0
                       ELSE SUM(CASE WHEN rating = 'positive' THEN 1 ELSE 0 END)
                            / SUM(CASE WHEN rating IN ('positive', 'negative') THEN 1 ELSE 0 END) * 100
                   END AS rate
            FROM chat_evaluation
            <where>
                create_date &gt;= DATE_SUB(CURRENT_DATE, INTERVAL 6 DAY)
                <if test="modelId != null and modelId != ''">AND model_id = #{modelId}</if>
                <if test="rating != null and rating != ''">AND rating = #{rating}</if>
                <if test="failureMode != null and failureMode != ''">AND failure_mode = #{failureMode}</if>
                <if test="startDateTime != null">AND create_date &gt;= #{startDateTime}</if>
                <if test="endDateTime != null">AND create_date &lt; #{endDateTime}</if>
            </where>
            GROUP BY DATE(create_date)
            ORDER BY DATE(create_date)
            </script>
            """)
    List<Map<String, Object>> selectApprovalTrend(
            @Param("modelId") String modelId,
            @Param("rating") String rating,
            @Param("failureMode") String failureMode,
            @Param("startDateTime") LocalDateTime startDateTime,
            @Param("endDateTime") LocalDateTime endDateTime);
}
