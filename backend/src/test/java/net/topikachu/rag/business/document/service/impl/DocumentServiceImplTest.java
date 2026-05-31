package net.topikachu.rag.business.document.service.impl;

import net.topikachu.rag.business.document.entity.Document;
import net.topikachu.rag.business.document.entity.DocumentStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.KnowledgeAclRefreshTaskMapper;
import net.topikachu.rag.business.document.service.EtlJobService;
import net.topikachu.rag.business.document.vo.UploadResult;
import net.topikachu.rag.observability.TracingSupport;
import net.topikachu.rag.service.etl.DocumentChunkMetadataBuilder;
import net.topikachu.rag.service.etl.KnowledgeParentBlockService;
import net.topikachu.rag.service.etl.MilvusWriteGateway;
import net.topikachu.rag.service.storage.ObjectStorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentServiceImplTest {

    @TempDir
    Path tempDir;

    private DocumentMapper documentMapper;
    private TracingSupport tracingSupport;
    private ObjectStorageService objectStorageService;
    private EtlJobService etlJobService;
    private KnowledgeParentBlockService parentBlockService;
    private PlatformTransactionManager transactionManager;
    private DocumentServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        documentMapper = mock(DocumentMapper.class);
        tracingSupport = mock(TracingSupport.class);
        objectStorageService = mock(ObjectStorageService.class);
        etlJobService = mock(EtlJobService.class);
        parentBlockService = mock(KnowledgeParentBlockService.class);
        transactionManager = mock(PlatformTransactionManager.class);

        service = new DocumentServiceImpl(
                documentMapper,
                tracingSupport,
                mock(MilvusWriteGateway.class),
                mock(DocumentChunkMetadataBuilder.class),
                mock(KnowledgeAclRefreshTaskMapper.class),
                etlJobService,
                parentBlockService,
                transactionManager,
                objectStorageService);

        ReflectionTestUtils.setField(service, "inputDirectory", tempDir.toString());
        ReflectionTestUtils.setField(service, "allowedExt", "pdf,doc,docx,txt,md");
        ReflectionTestUtils.setField(service, "maxSizeBytes", 52428800L);
        ReflectionTestUtils.setField(service, "aclRefreshMaxRetries", 5);
        ReflectionTestUtils.setField(service, "aclRefreshBatchSize", 20);

        doAnswer(inv -> inv.getArgument(2)).when(tracingSupport)
                .traceMono(anyString(), anyMap(), any());
        when(objectStorageService.putObject(anyString(), any(Path.class), anyString()))
                .thenReturn(Mono.empty());
        when(objectStorageService.deleteObject(anyString()))
                .thenReturn(Mono.empty());
        when(objectStorageService.exists(anyString()))
                .thenReturn(Mono.just(true));
        when(parentBlockService.deleteByDocUuid(anyString()))
                .thenReturn(Mono.empty());
        Path stubFile = tempDir.resolve("stub.txt");
        Files.write(stubFile, "stub".getBytes());
        when(objectStorageService.getObject(anyString()))
                .thenReturn(Mono.just(Files.newInputStream(stubFile)));
    }

    private FilePart mockFilePart(String filename, MediaType mediaType, Path sourceFile) {
        FilePart filePart = mock(FilePart.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mediaType);
        when(filePart.headers()).thenReturn(headers);
        when(filePart.filename()).thenReturn(filename);
        when(filePart.transferTo(any(Path.class))).thenAnswer(inv -> {
            Path target = (Path) inv.getArgument(0);
            Files.copy(sourceFile, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return Mono.empty();
        });
        return filePart;
    }

    @Test
    void uploadNewDocumentCompletesFullChain() throws Exception {
        Path sourceFile = tempDir.resolve("source.pdf");
        Files.write(sourceFile, "test pdf content for hash".getBytes());
        FilePart filePart = mockFilePart("test.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        UploadResult result = service.upload(filePart, null, false, "user1", List.of("tag1")).block();

        verify(objectStorageService).putObject(anyString(), any(Path.class), anyString());
        verify(etlJobService).queueDocumentIngestionSync(any(Document.class), anyString(), eq("user1"));
        assert result != null;
        assert result.isCreated();
        assert "test.pdf".equals(result.getFileName());
        assert DocumentStatus.UPLOADED.name().equals(result.getStatus());
        assert result.getFileHash() != null && !result.getFileHash().isEmpty();
    }

    @Test
    void uploadExistingDocumentReturnsCachedResultWhenNotOverwrite() throws Exception {
        Path sourceFile = tempDir.resolve("existing.pdf");
        Files.write(sourceFile, "duplicate content".getBytes());
        FilePart filePart = mockFilePart("existing.pdf", MediaType.APPLICATION_PDF, sourceFile);

        Document existing = new Document();
        existing.setDocUuid("existing-uuid");
        existing.setFileName("existing.pdf");
        existing.setStatus(DocumentStatus.COMPLETED.name());
        existing.setFileHash("abc123");
        when(documentMapper.selectOne(any())).thenReturn(existing);

        UploadResult result = service.upload(filePart, null, false, "user1", List.of()).block();

        verify(objectStorageService, never()).putObject(anyString(), any(Path.class), anyString());
        verify(etlJobService, never()).queueDocumentIngestionSync(any(), anyString(), anyString());
        assert result != null;
        assert !result.isCreated();
        assert "existing-uuid".equals(result.getDocUuid());
    }

    @Test
    void uploadExistingDocumentThrowsWhenOverwrite() throws Exception {
        Path sourceFile = tempDir.resolve("overwrite.pdf");
        Files.write(sourceFile, "content to overwrite".getBytes());
        FilePart filePart = mockFilePart("overwrite.pdf", MediaType.APPLICATION_PDF, sourceFile);

        Document existing = new Document();
        existing.setFileName("existing-name.pdf");
        when(documentMapper.selectOne(any())).thenReturn(existing);

        StepVerifier.create(service.upload(filePart, null, true, "user1", List.of()))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("The file already exists"))
                .verify();

        verify(etlJobService, never()).queueDocumentIngestionSync(any(), anyString(), anyString());
    }

    @Test
    void uploadCleansUpObjectStorageWhenPersistFails() throws Exception {
        Path sourceFile = tempDir.resolve("fail-persist.pdf");
        Files.write(sourceFile, "content that fails db".getBytes());
        FilePart filePart = mockFilePart("fail-persist.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        doThrow(new RuntimeException("DB insert failed"))
                .when(etlJobService).queueDocumentIngestionSync(any(Document.class), anyString(), anyString());

        StepVerifier.create(service.upload(filePart, null, false, "user1", List.of()))
                .expectErrorMatches(err -> err instanceof RuntimeException
                        && "DB insert failed".equals(err.getMessage()))
                .verify();

        verify(objectStorageService).putObject(anyString(), any(Path.class), anyString());
        verify(objectStorageService).deleteObject(anyString());
    }

    @Test
    void uploadRejectsInvalidExtension() throws Exception {
        Path sourceFile = tempDir.resolve("bad.exe");
        Files.write(sourceFile, "malicious content".getBytes());
        FilePart filePart = mockFilePart("bad.exe", MediaType.APPLICATION_OCTET_STREAM, sourceFile);

        StepVerifier.create(service.upload(filePart, null, false, "user1", List.of()))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("File extension not allowed"))
                .verify();

        verify(objectStorageService, never()).putObject(anyString(), any(Path.class), anyString());
    }

    @Test
    void uploadRejectsOversizedFile() throws Exception {
        ReflectionTestUtils.setField(service, "maxSizeBytes", 10L);

        Path sourceFile = tempDir.resolve("large.pdf");
        Files.write(sourceFile, "this file is way too large for the tiny limit".getBytes());
        FilePart filePart = mockFilePart("large.pdf", MediaType.APPLICATION_PDF, sourceFile);

        StepVerifier.create(service.upload(filePart, null, false, "user1", List.of()))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("File too large"))
                .verify();
    }

    @Test
    void temporaryFileIsCleanedUpAfterUpload() throws Exception {
        Path sourceFile = tempDir.resolve("cleanup.pdf");
        Files.write(sourceFile, "cleanup test content".getBytes());
        FilePart filePart = mockFilePart("cleanup.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.upload(filePart, null, false, "user1", List.of()).block();

        long tempFileCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("upload_"))
                .count();
        assert tempFileCount == 0 : "Temporary files should be cleaned up after upload";
    }

    @Test
    void temporaryFileIsCleanedUpOnError() throws Exception {
        Path sourceFile = tempDir.resolve("error-cleanup.pdf");
        Files.write(sourceFile, "content that will error".getBytes());
        FilePart filePart = mockFilePart("error-cleanup.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);
        doThrow(new RuntimeException("persist failed"))
                .when(etlJobService).queueDocumentIngestionSync(any(Document.class), anyString(), anyString());

        service.upload(filePart, null, false, "user1", List.of()).onErrorResume(e -> Mono.empty()).block();

        long tempFileCount = Files.list(tempDir)
                .filter(p -> p.getFileName().toString().startsWith("upload_"))
                .count();
        assert tempFileCount == 0 : "Temporary files should be cleaned up even on error";
    }

    @Test
    void uploadSkipsStorageAndPersistForDuplicateHash() throws Exception {
        Path sourceFile = tempDir.resolve("dedup.pdf");
        Files.write(sourceFile, "dedup content".getBytes());
        FilePart filePart = mockFilePart("dedup.pdf", MediaType.APPLICATION_PDF, sourceFile);

        Document existing = new Document();
        existing.setDocUuid("dup-uuid");
        existing.setFileName("dedup.pdf");
        existing.setStatus(DocumentStatus.COMPLETED.name());
        when(documentMapper.selectOne(any())).thenReturn(existing);

        UploadResult result = service.upload(filePart, null, false, "user1", List.of()).block();

        assert result != null;
        assert !result.isCreated();
        assert "dup-uuid".equals(result.getDocUuid());
        verify(objectStorageService, never()).putObject(anyString(), any(Path.class), anyString());
        verify(etlJobService, never()).queueDocumentIngestionSync(any(), anyString(), anyString());
    }

    @Test
    void uploadAssignsCorrectObjectKeyToDocument() throws Exception {
        Path sourceFile = tempDir.resolve("keytest.pdf");
        Files.write(sourceFile, "key test content".getBytes());
        FilePart filePart = mockFilePart("keytest.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.upload(filePart, null, false, "user1", List.of()).block();

        verify(objectStorageService).putObject(
                argThat(key -> key.startsWith("documents/") && key.endsWith("/keytest.pdf")),
                any(Path.class),
                eq("application/pdf"));
    }

    @Test
    void uploadPassesCorrectUserIdToTransactionService() throws Exception {
        Path sourceFile = tempDir.resolve("userid.pdf");
        Files.write(sourceFile, "user id test".getBytes());
        FilePart filePart = mockFilePart("userid.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.insert(any(Document.class))).thenReturn(1);

        service.upload(filePart, null, false, "specific-user", List.of("t1")).block();

        verify(etlJobService).queueDocumentIngestionSync(
                any(Document.class), anyString(), eq("specific-user"));
    }

    @Test
    void uploadNoStorageRollbackWhenPutFails() throws Exception {
        ObjectStorageService failOss = mock(ObjectStorageService.class);
        EtlJobService failEtlJobService = mock(EtlJobService.class);
        when(failOss.putObject(anyString(), any(Path.class), anyString()))
                .thenReturn(Mono.error(new RuntimeException("storage down")));
        when(failOss.deleteObject(anyString())).thenReturn(Mono.empty());

        DocumentServiceImpl failService = new DocumentServiceImpl(
                documentMapper, tracingSupport, mock(MilvusWriteGateway.class),
                mock(DocumentChunkMetadataBuilder.class), mock(KnowledgeAclRefreshTaskMapper.class),
                failEtlJobService, parentBlockService, transactionManager, failOss);
        ReflectionTestUtils.setField(failService, "inputDirectory", tempDir.toString());
        ReflectionTestUtils.setField(failService, "allowedExt", "pdf,doc,docx,txt,md");
        ReflectionTestUtils.setField(failService, "maxSizeBytes", 52428800L);

        Path sourceFile = tempDir.resolve("put-fail.pdf");
        Files.write(sourceFile, "put fails content".getBytes());
        FilePart filePart = mockFilePart("put-fail.pdf", MediaType.APPLICATION_PDF, sourceFile);

        when(documentMapper.selectOne(any())).thenReturn(null);

        StepVerifier.create(failService.upload(filePart, null, false, "user1", List.of()))
                .expectErrorMatches(err -> err instanceof RuntimeException
                        && "storage down".equals(err.getMessage()))
                .verify();

        verify(failOss, never()).deleteObject(anyString());
        verify(failEtlJobService, never()).queueDocumentIngestionSync(any(), anyString(), anyString());
    }
}
