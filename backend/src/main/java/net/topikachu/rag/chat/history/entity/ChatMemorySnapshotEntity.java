package net.topikachu.rag.chat.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_memory_snapshot")
public class ChatMemorySnapshotEntity {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private String conversationId;

    @TableField("message_blob")
    private byte[] messageBlob;

    private Integer messageCount;
    private String serializer;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;
}
