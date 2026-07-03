package net.topikachu.rag.chat.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_message")
public class ChatMessageEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String sessionId;
    private String conversationId;
    private String userId;
    private String role;
    private String content;

    @TableField("message_blob")
    private byte[] messageBlob;

    private Integer messageIndex;
    private String modelId;
    private String mode;
    private LocalDateTime createDate;
}
