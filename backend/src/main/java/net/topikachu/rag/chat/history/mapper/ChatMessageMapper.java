package net.topikachu.rag.chat.history.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.chat.history.entity.ChatMessageEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {

    @Select("""
            SELECT COALESCE(MAX(message_index), 0)
            FROM chat_message
            WHERE session_id = #{sessionId}
            """)
    int selectMaxMessageIndex(@Param("sessionId") String sessionId);

    @Select("""
            SELECT *
            FROM chat_message
            WHERE session_id = #{sessionId}
            ORDER BY message_index ASC
            """)
    List<ChatMessageEntity> selectBySessionId(@Param("sessionId") String sessionId);
}
