package net.topikachu.rag.business.document.controller;

import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.business.document.service.DocumentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    @Autowired
    private DocumentService documentService;

    @PostMapping("/docs/upload")
    public AjaxResult upLoad(
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwriet
            ) throws IOException {

        Boolean result = documentService.upload(file,fileName,overwriet);

        return AjaxResult.success(result);
    }
}
