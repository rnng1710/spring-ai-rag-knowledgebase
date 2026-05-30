package net.topikachu.rag.service.etl;

import net.topikachu.rag.config.OcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfScanDetectorTest {

	@TempDir
	Path tempDir;

	@Test
	void textPdfIsNotDetectedAsScan() throws Exception {
		Path pdfPath = tempDir.resolve("text.pdf");
		writePdfWithText(pdfPath, "Hello PDFBox OCR");

		OcrProperties ocrProperties = new OcrProperties();
		ocrProperties.getPdf().setNativeTextThreshold(10);

		PdfScanDetector detector = new PdfScanDetector(ocrProperties);
		PdfScanDetector.ScanAnalysis analysis = detector.analyze(pdfPath);

		assertFalse(analysis.scanDetected());
		assertTrue(analysis.meaningfulChars() >= 10);
		assertTrue(analysis.averageMeaningfulCharsPerPage() >= 10);
	}

	@Test
	void blankPdfIsDetectedAsScan() throws Exception {
		Path pdfPath = tempDir.resolve("blank.pdf");
		try (PDDocument document = new PDDocument()) {
			document.addPage(new PDPage());
			document.save(pdfPath.toFile());
		}

		OcrProperties ocrProperties = new OcrProperties();
		ocrProperties.getPdf().setNativeTextThreshold(1);

		PdfScanDetector detector = new PdfScanDetector(ocrProperties);
		PdfScanDetector.ScanAnalysis analysis = detector.analyze(pdfPath);

		assertTrue(analysis.scanDetected());
	}

	@Test
	void sparseTextAcrossManyPagesIsDetectedAsScan() throws Exception {
		Path pdfPath = tempDir.resolve("sparse-text.pdf");
		try (PDDocument document = new PDDocument()) {
			for (int i = 0; i < 10; i++) {
				PDPage page = new PDPage();
				document.addPage(page);
			}
			try (PDPageContentStream stream = new PDPageContentStream(document, document.getPage(0))) {
				stream.beginText();
				stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				stream.newLineAtOffset(50, 700);
				stream.showText("tiny");
				stream.endText();
			}
			document.save(pdfPath.toFile());
		}

		OcrProperties ocrProperties = new OcrProperties();
		ocrProperties.getPdf().setNativeTextThreshold(2);

		PdfScanDetector detector = new PdfScanDetector(ocrProperties);
		PdfScanDetector.ScanAnalysis analysis = detector.analyze(pdfPath);

		assertTrue(analysis.scanDetected());
		assertTrue(analysis.averageMeaningfulCharsPerPage() < 2);
	}

	private void writePdfWithText(Path path, String text) throws IOException {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage();
			document.addPage(page);
			try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
				stream.beginText();
				stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
				stream.newLineAtOffset(50, 700);
				stream.showText(text);
				stream.endText();
			}
			document.save(path.toFile());
		}
	}
}
