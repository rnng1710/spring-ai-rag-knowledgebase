package net.topikachu.rag.chat.history.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("chat_session")
public class ChatSessionEntity {

    @TableId(value = "id", type = IdType.INPUT)
    private String id;

    private String conversationId;
    private String userId;
    private String title;
    private String titleStatus;
    private Boolean deleted;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createDate;
    private LocalDateTime updateDate;
}
