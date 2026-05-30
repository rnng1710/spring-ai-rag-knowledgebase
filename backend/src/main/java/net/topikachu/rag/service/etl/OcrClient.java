package net.topikachu.rag.service.etl;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.config.OcrProperties;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Component
@Slf4j
public class OcrClient {

	private final WebClient webClient;
	private final OcrProperties ocrProperties;
	private final Duration timeout;

	public OcrClient(WebClient.Builder webClientBuilder, OcrProperties ocrProperties) {
		this.ocrProperties = ocrProperties;
		this.webClient = webClientBuilder.baseUrl(ocrProperties.getBaseUrl()).build();
		this.timeout = Duration.ofMillis(ocrProperties.getTimeoutMs());
	}

	public Mono<OcrPdfResponse> ocrPdf(Path path) {
		var body = new LinkedMultiValueMap<String, Object>();
		body.add("file", new FileSystemResource(path));

		return webClient.post()
				.uri("/ocr/pdf")
				.contentType(MediaType.MULTIPART_FORM_DATA)
				.body(BodyInserters.fromMultipartData(body))
				.retrieve()
				.bodyToMono(OcrPdfResponse.class)
				.timeout(timeout)
				.doOnSubscribe(ignored -> log.info("Calling OCR service for PDF: {}", path.getFileName()))
				.doOnError(error -> log.error("OCR service call failed for {}: {}", path.getFileName(), error.getMessage()));
	}

	public record OcrPdfResponse(List<OcrPage> pages, OcrMeta meta) {
	}

	public record OcrPage(Integer pageNumber, String text, List<OcrLine> lines) {
	}

	public record OcrLine(String text, Double score) {
	}

	public record OcrMeta(
			String engine,
			String ocrVersion,
			String textDetectionModelName,
			String textRecognitionModelName,
			String lang,
			String device,
			Integer pageCount) {
	}
}
