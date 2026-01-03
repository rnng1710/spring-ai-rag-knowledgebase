package net.topikachu.rag.business.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import net.topikachu.rag.common.BaseEntity;

@Data
@TableName("knowledge_document")
public class Document extends BaseEntity {

    /**
     * file UUID
     */
    @TableField("DOC_UUID")
    private String docUuid;

    /**
     * file name
     */
    @TableField("FILE_NAME")
    private String fileName;

    /**
     * file name
     */
    @TableField("STATUS")
    private String status;

    @TableField("FILE_HASH")
    private String fileHash;
}
