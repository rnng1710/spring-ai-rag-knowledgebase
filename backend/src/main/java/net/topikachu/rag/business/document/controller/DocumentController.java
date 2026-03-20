package net.topikachu.rag.business.document.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.business.document.service.DocumentService;
import net.topikachu.rag.common.AjaxResult;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Slf4j
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/docs/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> upLoad(
            @RequestPart("file") Mono<FilePart> file,
            @RequestParam(value = "fileName", required = false) String fileName,
            @RequestParam(value = "overwrite", defaultValue = "false") boolean overwrite,
            @RequestParam(value = "tags", required = false) List<String> tags,
            Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> file.flatMap(part -> documentService.upload(
                                part,
                                fileName,
                                overwrite,
                                StringUtils.hasText(userId) ? userId : null,
                                tags))
                        .map(AjaxResult::success));
    }

    @PostMapping(path = "/upload/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> uploadBatch(
            @RequestPart("files") Flux<FilePart> files,
            @RequestParam(defaultValue = "false") boolean overwrite,
            @RequestParam(value = "tags", required = false) List<String> tags,
            Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    String effectiveUserId = StringUtils.hasText(userId) ? userId : null;
                    log.info("Batch upload requested by user={}, overwrite={}, tags={}", effectiveUserId, overwrite, tags);
                    return documentService.uploadBatch(files, overwrite, effectiveUserId, tags)
                            .map(AjaxResult::success);
                });
    }

    @GetMapping("/docs")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword) {
        return documentService.listDocuments(page, size, keyword)
                .map(AjaxResult::success);
    }

    @DeleteMapping("/docs/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> remove(@PathVariable Long id) {
        return documentService.removeDocumentById(id)
                .thenReturn(AjaxResult.success());
    }

    @DeleteMapping("/docs")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> removeBatch(@RequestBody List<Long> ids) {
        return documentService.removeDocumentsBatch(ids)
                .thenReturn(AjaxResult.success());
    }

    @GetMapping("/tags")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<AjaxResult> getTags() {
        return documentService.getAllTags()
                .map(AjaxResult::success);
    }

    @PostMapping("/docs/{id}/retry")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> retry(@PathVariable Long id, Mono<Principal> principalMono) {
        return principalMono.map(Principal::getName)
                .defaultIfEmpty("")
                .flatMap(userId -> {
                    String effectiveUserId = StringUtils.hasText(userId) ? userId : null;
                    log.info("Retry ingestion requested by user={}, docId={}", effectiveUserId, id);
                    return documentService.retryIngestion(id, effectiveUserId)
                            .thenReturn(AjaxResult.success("Retry started", null));
                });
    }
}
