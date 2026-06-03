package net.topikachu.rag.service.etl;

import net.topikachu.rag.config.OcrProperties;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

@Component
public class PdfScanDetector {

	private final OcrProperties ocrProperties;

	public PdfScanDetector(OcrProperties ocrProperties) {
		this.ocrProperties = ocrProperties;
	}

	public ScanAnalysis analyze(Path path) throws IOException {
		try (PDDocument document = Loader.loadPDF(path.toFile())) {
			// 使用 PDFBox 尝试提取内嵌文本层
			PDFTextStripper stripper = new PDFTextStripper();
			String extractedText = stripper.getText(document);
			// 统计有意义的字符数（排除空白和控制字符）
			int meaningfulChars = countMeaningfulChars(extractedText);
			int pageCount = Math.max(document.getNumberOfPages(), 1);
			double averageMeaningfulCharsPerPage = meaningfulChars / (double) pageCount;
			// 核心判定：每页平均有意义字符数低于阈值 → 判定为扫描件（图片PDF，无文本层）
			boolean scanDetected = averageMeaningfulCharsPerPage < ocrProperties.getPdf().getNativeTextThreshold();
			return new ScanAnalysis(scanDetected, meaningfulChars, pageCount, averageMeaningfulCharsPerPage);
		}
	}

	static int countMeaningfulChars(String text) {
		if (text == null || text.isBlank()) {
			return 0;
		}
		return (int) text.codePoints()
				.filter(codePoint -> !Character.isWhitespace(codePoint))
				.filter(codePoint -> !Character.isISOControl(codePoint))
				.count();
	}

	public record ScanAnalysis(
			boolean scanDetected,
			int meaningfulChars,
			int pageCount,
			double averageMeaningfulCharsPerPage) {
	}
}
