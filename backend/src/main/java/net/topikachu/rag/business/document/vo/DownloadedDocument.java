package net.topikachu.rag.business.document.vo;

import java.io.InputStream;

public record DownloadedDocument(String fileName, InputStream inputStream) {
}
