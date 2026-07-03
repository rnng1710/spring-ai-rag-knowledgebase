package net.topikachu.rag.chat.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.chat.history.entity.ChatSessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionEntity> {

    @Select("""
            <script>
            SELECT *
            FROM chat_session
            WHERE user_id = #{userId}
              AND deleted = 0
            <if test="keyword != null and keyword != ''">
              AND (
                  title LIKE CONCAT('%', #{keyword}, '%')
                  OR EXISTS (
                      SELECT 1
                      FROM chat_message m
                      WHERE m.session_id = chat_session.id
                        AND m.content LIKE CONCAT('%', #{keyword}, '%')
                  )
              )
            </if>
            ORDER BY last_message_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ChatSessionEntity> selectVisibleSessions(
            @Param("userId") String userId,
            @Param("keyword") String keyword,
            @Param("limit") int limit,
            @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM chat_session
            WHERE user_id = #{userId}
              AND deleted = 0
            <if test="keyword != null and keyword != ''">
              AND (
                  title LIKE CONCAT('%', #{keyword}, '%')
                  OR EXISTS (
                      SELECT 1
                      FROM chat_message m
                      WHERE m.session_id = chat_session.id
                        AND m.content LIKE CONCAT('%', #{keyword}, '%')
                  )
              )
            </if>
            </script>
            """)
    long countVisibleSessions(@Param("userId") String userId, @Param("keyword") String keyword);
}
