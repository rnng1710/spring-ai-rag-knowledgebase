package net.topikachu.rag.service.etl;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class OcrPdfDocumentReader {

	private final OcrClient ocrClient;

	public OcrPdfDocumentReader(OcrClient ocrClient) {
		this.ocrClient = ocrClient;
	}

	// .block() 调用远程 OCR 服务：ETL 管线已在 boundedElastic 线程上运行，阻塞不会影响事件循环
	public List<Document> read(Path path) {
		OcrClient.OcrPdfResponse response = ocrClient.ocrPdf(path).block();
		if (response == null || response.pages() == null || response.pages().isEmpty()) {
			throw new IllegalStateException("OCR returned no pages for PDF: " + path.getFileName());
		}

		List<Document> documents = new ArrayList<>();
		for (OcrClient.OcrPage page : response.pages()) {
			String text = page.text() == null ? "" : page.text().trim();
			if (text.isBlank()) {
				continue;
			}
			Map<String, Object> metadata = new HashMap<>();
			metadata.put("page_number", page.pageNumber() == null ? documents.size() + 1 : page.pageNumber());
			metadata.put("file_name", path.getFileName().toString());
			documents.add(new Document(text, metadata));
		}

		if (documents.isEmpty()) {
			throw new IllegalStateException("OCR returned only empty text for PDF: " + path.getFileName());
		}

		return documents;
	}
}
