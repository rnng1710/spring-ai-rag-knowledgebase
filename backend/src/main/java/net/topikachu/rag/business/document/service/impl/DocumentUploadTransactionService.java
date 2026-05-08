package net.topikachu.rag.business.document.service.impl;


import lombok.RequiredArgsConstructor;
import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class DocumentUploadTransactionService {
    private final DocumentMapper documentMapper;
    private final EtlJobService etlJobService;

    @Transactional(rollbackFor = Exception.class)
    public void createDocumentAndQueueJob(Document doc, String objectKey, String userId) {
        int inserted = documentMapper.insert(doc);
        if(inserted != 1){
            throw new IllegalStateException("Insert document failed");
        }
        etlJobService.queueDocumentIngestion(doc, objectKey, userId);
    }
}
