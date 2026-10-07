package net.topikachu.rag.evaluation.controller;

import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.auth.CurrentUserContext;
import net.topikachu.rag.auth.CurrentUserContextService;
import net.topikachu.rag.autoevaluation.service.AutoEvaluationService;
import net.topikachu.rag.common.AjaxResult;
import net.topikachu.rag.evaluation.service.EvaluationPersistenceService;
import net.topikachu.rag.evaluation.service.EvaluationQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.time.LocalDate;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1")
@Slf4j
public class EvaluationController {

    private final EvaluationPersistenceService persistenceService;
    private final EvaluationQueryService queryService;
    private final AutoEvaluationService autoEvaluationService;
    private final CurrentUserContextService currentUserContextService;

    public EvaluationController(EvaluationPersistenceService persistenceService,
                                EvaluationQueryService queryService,
                                AutoEvaluationService autoEvaluationService,
                                CurrentUserContextService currentUserContextService) {
        this.persistenceService = persistenceService;
        this.queryService = queryService;
        this.autoEvaluationService = autoEvaluationService;
        this.currentUserContextService = currentUserContextService;
    }

    @PatchMapping("/evaluation/{msgId}/feedback")
    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    public Mono<ResponseEntity<AjaxResult>> submitFeedback(
            @PathVariable String msgId,
            @RequestBody FeedbackRequest request,
            Mono<Principal> principalMono) {
        return principalMono.flatMap(principal -> {
                    CurrentUserContext ctx = currentUserContextService.resolveByUsername(principal.getName());
                    return persistenceService.updateFeedback(
                            msgId, ctx.userId(), ctx.isAdmin(),
                            request.rating(), request.failureMode());
                })
                .map(updated -> updated
                        ? ResponseEntity.ok(AjaxResult.success())
                        : ResponseEntity.badRequest()
                                .body(AjaxResult.error("更新失败：记录不存在或无权操作")));
    }

    @GetMapping("/admin/evaluation/conversations")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> listConversations(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String rating,
            @RequestParam(required = false) String failureMode,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return queryService.queryConversations(
                        page, size, modelId, rating, failureMode,
                        parseDate(startDate), parseDate(endDate))
                .map(AjaxResult::success);
    }

    @PatchMapping("/admin/evaluation/{evaluationId}/reference")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<ResponseEntity<AjaxResult>> updateReference(
            @PathVariable String evaluationId,
            @RequestBody ReferenceRequest request,
            Mono<Principal> principalMono) {
        return principalMono.flatMap(principal -> {
                    CurrentUserContext ctx = currentUserContextService.resolveByUsername(principal.getName());
                    return persistenceService.updateReference(
                            evaluationId,
                            ctx.userId(),
                            request.reference());
                })
                .map(updated -> updated
                        ? ResponseEntity.ok(AjaxResult.success())
                        : ResponseEntity.badRequest()
                                .body(AjaxResult.error("更新失败：记录不存在")));
    }

    @PostMapping("/admin/evaluation/auto/trigger")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> triggerAutoEvaluation() {
        return autoEvaluationService.runAutoEvaluation()
                .map(AjaxResult::success)
                .onErrorResume(e -> Mono.just(AjaxResult.warn(e.getMessage())));
    }

    @GetMapping("/admin/evaluation/auto/stats")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> autoStats() {
        return autoEvaluationService.queryAutoStats()
                .map(AjaxResult::success);
    }

    @GetMapping("/admin/evaluation/auto/scores")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> autoScores(@RequestParam String evaluationId) {
        return autoEvaluationService.queryScoresByEvaluationId(evaluationId)
                .map(AjaxResult::success);
    }

    @GetMapping("/admin/evaluation/auto/runs")
    @PreAuthorize("hasRole('ADMIN')")
    public Mono<AjaxResult> autoRuns() {
        return autoEvaluationService.queryRunHistory()
                .map(AjaxResult::success);
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (Exception e) {
            return null;
        }
    }

    record FeedbackRequest(
            String rating,
            String failureMode) {
    }

    record ReferenceRequest(String reference) {
    }
}
