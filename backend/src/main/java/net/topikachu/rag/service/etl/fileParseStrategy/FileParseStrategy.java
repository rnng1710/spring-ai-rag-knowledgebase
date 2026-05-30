package net.topikachu.rag.service.etl.fileParseStrategy;

import net.topikachu.rag.service.etl.EtlPipeline;
import org.springframework.ai.document.Document;

import java.util.List;

//pdf,doc,docx,txt,md
public interface FileParseStrategy {

    List<Document> readFile(String fileType, EtlPipeline.EtlContext ctx);

    String getFileType();
}
