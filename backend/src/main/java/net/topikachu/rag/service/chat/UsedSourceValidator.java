package net.topikachu.rag.service.chat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class UsedSourceValidator {

    public static final String UNRELIABLE_SOURCE_MESSAGE = "无法可靠生成带溯源的答案，请缩小问题范围或稍后重试。";
    public static final String REASON_ANSWER_MISSING = "answer_missing";
    public static final String REASON_USED_SOURCES_EMPTY = "used_sources_empty";
    public static final String REASON_EVIDENCE_ID_MISSING = "evidence_id_missing";
    public static final String REASON_EVIDENCE_ID_NOT_IN_CANDIDATES = "evidence_id_not_in_candidates";
    public static final String REASON_INVALID_ANSWER_TYPE = "invalid_answer_type";

    public List<UsedSource> validate(SourcedAnswerResult result, List<Document> candidates) {
        if (result == null || !StringUtils.hasText(result.answer())) {
            throw validationFailure(REASON_ANSWER_MISSING, null, candidates);
        }

        boolean refusal = "refusal".equalsIgnoreCase(result.answerType());
        boolean factual = "factual".equalsIgnoreCase(result.answerType());
        if (!refusal && !factual) {
            throw validationFailure(REASON_INVALID_ANSWER_TYPE, result, candidates);
        }
        List<String> requestedSources = result.usedSources() == null ? List.of() : result.usedSources();
        if (refusal) {
            return List.of();
        }
        if (requestedSources.isEmpty()) {
            throw validationFailure(REASON_USED_SOURCES_EMPTY, result, candidates);
        }

        Map<String, Document> candidatesByEvidenceId = new LinkedHashMap<>();
        for (Document candidate : candidates == null ? List.<Document>of() : candidates) {
            String evidenceId = evidenceId(candidate);
            if (StringUtils.hasText(evidenceId)) {
                candidatesByEvidenceId.put(evidenceId, candidate);
            }
        }

        List<UsedSource> validated = new ArrayList<>();
        for (String requestedEvidenceId : requestedSources) {
            if (!StringUtils.hasText(requestedEvidenceId)) {
                throw validationFailure(REASON_EVIDENCE_ID_MISSING, result, candidates);
            }
            Document candidate = candidatesByEvidenceId.get(requestedEvidenceId.trim());
            if (candidate == null) {
                throw validationFailure(REASON_EVIDENCE_ID_NOT_IN_CANDIDATES, result, candidates);
            }
            validated.add(fromDocument(candidate));
        }
        return collapseDisplayedSources(validated);
    }

    public List<UsedSource> fromDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        return collapseDisplayedSources(documents.stream()
                .map(this::fromDocument)
                .toList());
    }

    public UsedSource fromDocument(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        return new UsedSource(
                evidenceId(document),
                stringValue(metadata.get("doc_uuid")),
                stringValue(metadata.get("file_name")),
                metadata.getOrDefault("page_number", metadata.get("page")),
                fileType(stringValue(metadata.get("file_name"))));
    }

    private List<UsedSource> collapseDisplayedSources(List<UsedSource> sources) {
        Map<String, UsedSource> unique = new LinkedHashMap<>();
        for (UsedSource source : sources) {
            if (source == null || !StringUtils.hasText(source.docUuid())) {
                continue;
            }
            String key = source.docUuid() + "|" + (source.pageNumber() == null ? "" : source.pageNumber());
            unique.putIfAbsent(key, source);
        }
        return List.copyOf(unique.values());
    }

    private String evidenceId(Document document) {
        if (document == null) {
            return null;
        }
        Object metadataEvidenceId = document.getMetadata().get("evidence_id");
        if (metadataEvidenceId != null && StringUtils.hasText(metadataEvidenceId.toString())) {
            return metadataEvidenceId.toString().trim();
        }
        return document.getId();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private String fileType(String fileName) {
        if (!StringUtils.hasText(fileName)) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(idx + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private SourceValidationException validationFailure(String reason, SourcedAnswerResult result, List<Document> candidates) {
        log.warn("Used source validation failed: reason={}, answerType={}, requestedSources={}, candidateCount={}",
                reason,
                result == null ? null : result.answerType(),
                result == null || result.usedSources() == null ? 0 : result.usedSources().size(),
                candidates == null ? 0 : candidates.size());
        return new SourceValidationException(UNRELIABLE_SOURCE_MESSAGE, reason);
    }
}
