package net.topikachu.rag.service.etl;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OcrPdfDocumentReaderTest {

	@Test
	void convertsOcrPagesToDocuments() {
		OcrClient ocrClient = mock(OcrClient.class);
		OcrPdfDocumentReader reader = new OcrPdfDocumentReader(ocrClient);
		Path path = Path.of("sample.pdf");

		OcrClient.OcrPdfResponse response = new OcrClient.OcrPdfResponse(
				List.of(
						new OcrClient.OcrPage(1, "Page 1 text", List.of()),
						new OcrClient.OcrPage(2, "Page 2 text", List.of())
				),
				new OcrClient.OcrMeta("PaddleOCR", "PP-OCRv5", "PP-OCRv5_mobile_det", "PP-OCRv5_mobile_rec", "ch", "cpu", 2)
		);

		when(ocrClient.ocrPdf(path)).thenReturn(Mono.just(response));

		List<Document> documents = reader.read(path);

		assertEquals(2, documents.size());
		assertEquals("Page 1 text", documents.get(0).getText());
		assertEquals(1, documents.get(0).getMetadata().get("page_number"));
		assertEquals("sample.pdf", documents.get(0).getMetadata().get("file_name"));
	}

	@Test
	void rejectsEmptyOcrResponse() {
		OcrClient ocrClient = mock(OcrClient.class);
		OcrPdfDocumentReader reader = new OcrPdfDocumentReader(ocrClient);
		Path path = Path.of("sample.pdf");

		when(ocrClient.ocrPdf(path)).thenReturn(Mono.just(new OcrClient.OcrPdfResponse(List.of(), null)));

		assertThrows(IllegalStateException.class, () -> reader.read(path));
	}
}
