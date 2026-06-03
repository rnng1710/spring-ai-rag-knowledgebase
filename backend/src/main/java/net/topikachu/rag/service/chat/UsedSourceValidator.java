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

    // 验证 LLM 回答中的引用：确保每个 usedSources 中的 evidence_id 都在候选文档中存在
    // 验证失败 → 抛异常，回答被拒绝，返回"无法可靠生成带溯源的答案"
    public List<UsedSource> validate(SourcedAnswerResult result, List<Document> candidates) {
        // 1. 回答必须有内容
        if (result == null || !StringUtils.hasText(result.answer())) {
            throw validationFailure(REASON_ANSWER_MISSING, null, candidates);
        }

        // 2. answerType 只能是 factual 或 refusal
        boolean refusal = "refusal".equalsIgnoreCase(result.answerType());
        boolean factual = "factual".equalsIgnoreCase(result.answerType());
        if (!refusal && !factual) {
            throw validationFailure(REASON_INVALID_ANSWER_TYPE, result, candidates);
        }
        List<String> requestedSources = result.usedSources() == null ? List.of() : result.usedSources();
        // 3. refusal 不要求引用，直接通过
        if (refusal) {
            return List.of();
        }
        // 4. factual 必须有至少一个引用
        if (requestedSources.isEmpty()) {
            throw validationFailure(REASON_USED_SOURCES_EMPTY, result, candidates);
        }

        // 5. 将候选文档按 evidence_id 建索引，O(1) 查找
        Map<String, Document> candidatesByEvidenceId = new LinkedHashMap<>();
        for (Document candidate : candidates == null ? List.<Document>of() : candidates) {
            String evidenceId = evidenceId(candidate);
            if (StringUtils.hasText(evidenceId)) {
                candidatesByEvidenceId.put(evidenceId, candidate);
            }
        }

        // 6. 逐个验证 LLM 声明的 evidence_id 是否在候选集合中
        List<UsedSource> validated = new ArrayList<>();
        for (String requestedEvidenceId : requestedSources) {
            // evidence_id 不能为空
            if (!StringUtils.hasText(requestedEvidenceId)) {
                throw validationFailure(REASON_EVIDENCE_ID_MISSING, result, candidates);
            }
            // evidence_id 必须在候选文档中存在（杜绝 LLM 幻觉引用）
            Document candidate = candidatesByEvidenceId.get(requestedEvidenceId.trim());
            if (candidate == null) {
                throw validationFailure(REASON_EVIDENCE_ID_NOT_IN_CANDIDATES, result, candidates);
            }
            validated.add(fromDocument(candidate));
        }
        // 7. 同文档同位置的引用去重合并展示
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
                sourceLocation(metadata),
                fileType(stringValue(metadata.get("file_name"))));
    }

    // 同文档同位置的多个 evidence_id 只保留第一条（去重合并展示，避免溯源列表冗余）
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

    // 解析溯源展示位置：四级 fallback 链
    // ① 显式 source_location（DOCX/MD 面包屑，如"学生纪律 > 开除程序"）
    // ② page_start/page_end（PDF 页码范围，如"3-4"或"5"）
    // ③ parent_index → "片段N"（无标题结构的非 PDF 文档）
    // ④ page_number / page（最老数据的兜底兼容）
    private Object sourceLocation(Map<String, Object> metadata) {
        // ① 优先：语义化溯源路径（DOCX/MD 策略写入的面包屑）
        Object sourceLocation = metadata.get("source_location");
        if (sourceLocation != null && StringUtils.hasText(sourceLocation.toString())) {
            return sourceLocation.toString().trim();
        }
        // ② 次选：PDF 页码范围
        Object pageStart = metadata.get("page_start");
        Object pageEnd = metadata.get("page_end");
        if (pageStart != null && pageEnd != null) {
            String start = pageStart.toString();
            String end = pageEnd.toString();
            // 单页 → 直接返回页码，跨页 → 返回"起始-结束"范围
            return start.equals(end) ? pageStart : start + "-" + end;
        }
        // ③ 再次：通用 parent_index → "片段N"
        Object parentIndex = metadata.get("parent_index");
        if (parentIndex != null) {
            return "片段" + parentIndex;
        }
        // ④ 兜底：旧版元数据的 page_number / page
        return metadata.getOrDefault("page_number", metadata.get("page"));
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
