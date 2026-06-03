package net.topikachu.rag.service.chat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsedSourceValidatorTest {

    private final UsedSourceValidator validator = new UsedSourceValidator();

    @Test
    void validatesOnlySourcesFromRetrievedCandidates() {
        Document candidate = new Document("content", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "handbook.pdf",
                "page_number", 3));
        SourcedAnswerResult result = new SourcedAnswerResult(
                "answer",
                "factual",
                List.of("ev-1"));

        List<UsedSource> usedSources = validator.validate(result, List.of(candidate));

        assertEquals(1, usedSources.size());
        assertEquals("ev-1", usedSources.get(0).evidenceId());
        assertEquals("doc-1", usedSources.get(0).docUuid());
        assertEquals("handbook.pdf", usedSources.get(0).fileName());
        assertEquals(3, usedSources.get(0).pageNumber());
    }

    @Test
    void rejectsFactualAnswerWithoutSources() {
        SourcedAnswerResult result = new SourcedAnswerResult("answer", "factual", List.of());

        SourceValidationException error = assertThrows(SourceValidationException.class,
                () -> validator.validate(result, List.of()));

        assertEquals(UsedSourceValidator.REASON_USED_SOURCES_EMPTY, error.getReason());
    }

    @Test
    void allowsRefusalWithoutSources() {
        SourcedAnswerResult result = new SourcedAnswerResult("无法可靠回答。", "refusal", List.of());

        List<UsedSource> usedSources = validator.validate(result, List.of());

        assertEquals(List.of(), usedSources);
    }

    @Test
    void collapsesSameDocumentPageSources() {
        Document first = new Document("first", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "handbook.pdf",
                "page_number", 3));
        Document second = new Document("second", Map.of(
                "evidence_id", "ev-2",
                "doc_uuid", "doc-1",
                "file_name", "handbook.pdf",
                "page_number", 3));
        SourcedAnswerResult result = new SourcedAnswerResult(
                "answer",
                "factual",
                List.of(
                        "ev-1",
                        "ev-2"));

        List<UsedSource> usedSources = validator.validate(result, List.of(first, second));

        assertEquals(1, usedSources.size());
    }

    @Test
    void usesCanonicalPdfPageRangeLocation() {
        Document candidate = new Document("content", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "handbook.pdf",
                "page_start", 3,
                "page_end", 4));
        SourcedAnswerResult result = new SourcedAnswerResult("answer", "factual", List.of("ev-1"));

        List<UsedSource> usedSources = validator.validate(result, List.of(candidate));

        assertEquals("3-4", usedSources.get(0).pageNumber());
    }

    @Test
    void collapsesNonPdfSourcesByParentIndexSegment() {
        Document first = new Document("first", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "policy.docx",
                "parent_index", 4));
        Document second = new Document("second", Map.of(
                "evidence_id", "ev-2",
                "doc_uuid", "doc-1",
                "file_name", "policy.docx",
                "parent_index", 4));
        SourcedAnswerResult result = new SourcedAnswerResult("answer", "factual", List.of("ev-1", "ev-2"));

        List<UsedSource> usedSources = validator.validate(result, List.of(first, second));

        assertEquals(1, usedSources.size());
        assertEquals("片段4", usedSources.get(0).pageNumber());
    }

    @Test
    void prefersExplicitSourceLocation() {
        Document candidate = new Document("content", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "policy.docx",
                "parent_index", 4,
                "source_location", "学生纪律 > 开除程序"));
        SourcedAnswerResult result = new SourcedAnswerResult("answer", "factual", List.of("ev-1"));

        List<UsedSource> usedSources = validator.validate(result, List.of(candidate));

        assertEquals("学生纪律 > 开除程序", usedSources.get(0).pageNumber());
    }

    @Test
    void rejectsMissingAnswerWithReason() {
        SourcedAnswerResult result = new SourcedAnswerResult("", "factual", List.of("ev-1"));

        SourceValidationException error = assertThrows(SourceValidationException.class,
                () -> validator.validate(result, List.of()));

        assertEquals(UsedSourceValidator.REASON_ANSWER_MISSING, error.getReason());
    }

    @Test
    void rejectsMissingEvidenceIdWithReason() {
        Document candidate = new Document("content", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "handbook.pdf"));
        SourcedAnswerResult result = new SourcedAnswerResult(
                "answer",
                "factual",
                List.of(""));

        SourceValidationException error = assertThrows(SourceValidationException.class,
                () -> validator.validate(result, List.of(candidate)));

        assertEquals(UsedSourceValidator.REASON_EVIDENCE_ID_MISSING, error.getReason());
    }

    @Test
    void rejectsInvalidAnswerTypeWithReason() {
        SourcedAnswerResult result = new SourcedAnswerResult(
                "answer",
                "direct_answer",
                List.of("ev-1"));

        SourceValidationException error = assertThrows(SourceValidationException.class,
                () -> validator.validate(result, List.of()));

        assertEquals(UsedSourceValidator.REASON_INVALID_ANSWER_TYPE, error.getReason());
    }

    @Test
    void rejectsEvidenceIdOutsideCandidatesWithReason() {
        Document candidate = new Document("content", Map.of(
                "evidence_id", "ev-1",
                "doc_uuid", "doc-1",
                "file_name", "handbook.pdf"));
        SourcedAnswerResult result = new SourcedAnswerResult(
                "answer",
                "factual",
                List.of("ev-2"));

        SourceValidationException error = assertThrows(SourceValidationException.class,
                () -> validator.validate(result, List.of(candidate)));

        assertEquals(UsedSourceValidator.REASON_EVIDENCE_ID_NOT_IN_CANDIDATES, error.getReason());
    }
}
