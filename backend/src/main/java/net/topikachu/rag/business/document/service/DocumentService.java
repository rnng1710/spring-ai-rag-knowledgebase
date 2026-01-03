package net.topikachu.rag.business.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.topikachu.rag.business.document.entity.Document;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface DocumentService extends IService<Document> {

    Boolean upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite) throws IOException;
}
