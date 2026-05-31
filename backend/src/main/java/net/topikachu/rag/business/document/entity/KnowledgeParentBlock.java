package net.topikachu.rag.business.document.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import net.topikachu.rag.common.BaseEntity;

import java.util.List;

@Data
@TableName(value = "knowledge_parent_block", autoResultMap = true)
public class KnowledgeParentBlock extends BaseEntity {

    @TableField("parent_block_id")
    private String parentBlockId;

    @TableField("doc_uuid")
    private String docUuid;

    @TableField("parent_index")
    private Integer parentIndex;

    @TableField("content")
    private String content;

    @TableField("file_name")
    private String fileName;

    @TableField("page_start")
    private Integer pageStart;

    @TableField("page_end")
    private Integer pageEnd;

    @TableField("space_code")
    private String spaceCode;

    @TableField(value = "tags", typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    @TableField("acl_version")
    private Integer aclVersion;

    @TableField("chunk_schema_version")
    private Integer chunkSchemaVersion;
}
