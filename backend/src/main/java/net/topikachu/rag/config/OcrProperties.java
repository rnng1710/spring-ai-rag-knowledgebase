package net.topikachu.rag.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "rag.ocr")
public class OcrProperties {

	private boolean enabled = false;

	private String baseUrl = "http://192.168.193.128:8100";

	private int timeoutMs = 120000;

	private Pdf pdf = new Pdf();

	@Data
	public static class Pdf {
		private int nativeTextThreshold = 32;
	}
}
