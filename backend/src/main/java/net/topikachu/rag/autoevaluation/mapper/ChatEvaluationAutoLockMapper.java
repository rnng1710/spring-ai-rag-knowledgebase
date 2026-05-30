package net.topikachu.rag.autoevaluation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import net.topikachu.rag.autoevaluation.entity.ChatEvaluationAutoLockEntity;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;

@Mapper
public interface ChatEvaluationAutoLockMapper extends BaseMapper<ChatEvaluationAutoLockEntity> {

    @Insert("""
            INSERT INTO chat_evaluation_auto_lock (lock_name, owner_id, locked_until)
            VALUES (#{lockName}, #{ownerId}, #{lockedUntil})
            ON DUPLICATE KEY UPDATE
                owner_id = IF(locked_until < #{now}, VALUES(owner_id), owner_id),
                locked_until = IF(locked_until < #{now}, VALUES(locked_until), locked_until)
            """)
    int acquireOrRefreshExpired(
            @Param("lockName") String lockName,
            @Param("ownerId") String ownerId,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now);

    @Select("""
            SELECT owner_id
            FROM chat_evaluation_auto_lock
            WHERE lock_name = #{lockName}
            """)
    String selectOwner(@Param("lockName") String lockName);

    @Delete("""
            DELETE FROM chat_evaluation_auto_lock
            WHERE lock_name = #{lockName}
              AND owner_id = #{ownerId}
            """)
    int release(
            @Param("lockName") String lockName,
            @Param("ownerId") String ownerId);
}
