package net.topikachu.rag.autoevaluation.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_evaluation_auto_lock")
public class ChatEvaluationAutoLockEntity {

    @TableId(value = "lock_name", type = IdType.INPUT)
    private String lockName;

    private String ownerId;
    private LocalDateTime lockedUntil;
    private LocalDateTime updateDate;
}
