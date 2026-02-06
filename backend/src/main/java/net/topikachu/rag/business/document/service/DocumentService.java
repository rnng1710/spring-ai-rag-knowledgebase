package net.topikachu.rag.business.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.vo.BatchUploadResponse;
import net.topikachu.rag.business.document.vo.UploadResult;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

public interface DocumentService extends IService<Document> {

    UploadResult upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            String userId,
            List<String> tags) throws IOException;

    /**
     * 批量上传（幂等）：对每个文件返回 created/docUuid/status/fileName/fileHash；单文件失败不影响其它文件
     */
    BatchUploadResponse uploadBatch(List<MultipartFile> files, boolean overwrite, String userId, List<String> tags);

    void removeDocumentById(Long id);

    void removeDocumentsBatch(List<Long> ids);

    List<String> getAllTags();

    /**
     * Retry ingestion for a FAILED document
     */
    void retryIngestion(Long id, String userId);
}
