package net.topikachu.rag.business.document.controller;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.business.document.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import net.topikachu.rag.business.document.entity.Document;
import org.springframework.util.StringUtils;
import java.io.IOException;
import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class DocumentController {

        @Autowired
        private DocumentService documentService;

        @PostMapping("/docs/upload")
        public AjaxResult upLoad(
                        @RequestPart("file") MultipartFile file,
                        @RequestParam(value = "fileName", required = false) String fileName,
                        @RequestParam(value = "overwrite", defaultValue = "false") boolean overwriet)
                        throws IOException {

                UploadResult result = documentService.upload(file, fileName, overwriet);

                return AjaxResult.success(result);
        }

        /**
         * Batch upload (idempotent): Repeated uploads return the existing
         * docUuid/status/fileName and do not trigger ingestion
         *
         * form-data:
         * - files: multifile
         * - overwrite: true/false（default false）
         */
        @PostMapping(path = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
        public AjaxResult uploadBatch(
                        @RequestPart("files") List<MultipartFile> files,
                        @RequestParam(defaultValue = "false") boolean overwrite,
                        Principal principal) {
                // Record the operator's actions for auditing (creating an audit report is very
                // useful)
                log.info("Batch upload requested by user={}, fileCount={}, overwrite={}",
                                principal == null ? "anonymous" : principal.getName(),
                                files == null ? 0 : files.size(),
                                overwrite);

                return AjaxResult.success(documentService.uploadBatch(files, overwrite));
        }

        @GetMapping("/docs")
        public AjaxResult list(
                        @RequestParam(defaultValue = "1") int page,
                        @RequestParam(defaultValue = "10") int size,
                        @RequestParam(required = false) String keyword) {
                Page<Document> p = new Page<>(page, size);
                LambdaQueryWrapper<Document> query = Wrappers.lambdaQuery();
                if (StringUtils.hasText(keyword)) {
                        query.like(Document::getFileName, keyword);
                }
                query.orderByDesc(Document::getCreateDate);
                return AjaxResult.success(documentService.page(p, query));
        }

        @DeleteMapping("/docs/{id}")
        public AjaxResult remove(@PathVariable Long id) {
                documentService.removeDocumentById(id);
                return AjaxResult.success();
        }

        @DeleteMapping("/docs")
        public AjaxResult removeBatch(@RequestBody List<Long> ids) {
                documentService.removeDocumentsBatch(ids);
                return AjaxResult.success();
        }
}
