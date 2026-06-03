package net.topikachu.rag.service.etl.fileParseStrategy;

import net.topikachu.rag.service.etl.ChunkUtils;
import net.topikachu.rag.service.etl.EtlPipeline;

/**
 * 文件解析策略：每种文件类型独立实现，负责读取、切分并构建父块-子块结构。
 * supports() 基于文件扩展名匹配，不检查文件内容/魔数。
 */
//pdf,doc,docx,txt,md
public interface FileParseStrategy {

    ChunkUtils.ParentChildDocuments readAndSplit(String fileType, EtlPipeline.EtlContext ctx);

    boolean supports(String fileType);
}
