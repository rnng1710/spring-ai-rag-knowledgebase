package net.topikachu.rag.evaluation.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.topikachu.rag.evaluation.dto.*;
import net.topikachu.rag.evaluation.entity.ChatEvaluationEntity;
import net.topikachu.rag.evaluation.mapper.ChatEvaluationMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class EvaluationQueryService {

    private final ChatEvaluationMapper mapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public EvaluationQueryService(ChatEvaluationMapper mapper,
                                  ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public Mono<EvaluationPageResult> queryConversations(
            int page, int size,
            String modelId, String rating, String failureMode,
            LocalDate startDate, LocalDate endDate) {

        return Mono.fromCallable(() -> {
                    EvaluationStats stats = computeStats(
                            modelId, rating, failureMode, startDate, endDate);

                    Page<ChatEvaluationEntity> resultPage = new Page<>(page, size);
                    LambdaQueryWrapper<ChatEvaluationEntity> listWrapper = buildFilterWrapper(
                            modelId, rating, failureMode, startDate, endDate);
                    listWrapper.orderByDesc(ChatEvaluationEntity::getCreateDate);
                    Page<ChatEvaluationEntity> pageResult = mapper.selectPage(resultPage, listWrapper);

                    List<ConversationItem> items = pageResult.getRecords().stream()
                            .map(this::toItem)
                            .toList();

                    return new EvaluationPageResult(stats, items, pageResult.getTotal());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private LambdaQueryWrapper<ChatEvaluationEntity> buildFilterWrapper(
            String modelId, String rating, String failureMode,
            LocalDate startDate, LocalDate endDate) {
        LambdaQueryWrapper<ChatEvaluationEntity> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(modelId)) {
            wrapper.eq(ChatEvaluationEntity::getModelId, modelId);
        }
        if (StringUtils.hasText(rating)) {
            wrapper.eq(ChatEvaluationEntity::getRating, rating);
        }
        if (StringUtils.hasText(failureMode)) {
            wrapper.eq(ChatEvaluationEntity::getFailureMode, failureMode);
        }
        if (startDate != null) {
            wrapper.ge(ChatEvaluationEntity::getCreateDate, startDate.atStartOfDay());
        }
        if (endDate != null) {
            wrapper.le(ChatEvaluationEntity::getCreateDate, endDate.plusDays(1).atStartOfDay());
        }
        return wrapper;
    }

    private EvaluationStats computeStats(
            String modelId, String rating, String failureMode,
            LocalDate startDate, LocalDate endDate) {
        LocalDateTime startDateTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endDateTime = endDate != null ? endDate.plusDays(1).atStartOfDay() : null;

        Map<String, Object> stats = mapper.selectStats(
                modelId, rating, failureMode, startDateTime, endDateTime);
        long total = toLong(stats.get("total"));
        long positive = toLong(stats.get("positive"));
        long negative = toLong(stats.get("negative"));
        long rated = positive + negative;
        double approvalRate = rated > 0 ? (double) positive / rated * 100 : 0;
        long pending = toLong(stats.get("pending"));

        Map<String, Object> topFailure = mapper.selectTopFailureMode(
                modelId, rating, failureMode, startDateTime, endDateTime);
        String topFailureMode = "";
        long topFailureCount = 0;
        if (topFailure != null) {
            topFailureMode = String.valueOf(topFailure.getOrDefault("failure_mode", ""));
            topFailureCount = toLong(topFailure.get("count"));
        }
        List<TrendPoint> trend = mapper.selectApprovalTrend(
                        modelId, rating, failureMode, startDateTime, endDateTime)
                .stream()
                .map(row -> new TrendPoint(String.valueOf(row.get("day")), toDouble(row.get("rate"))))
                .toList();

        return new EvaluationStats(total, approvalRate, 0, pending, topFailureMode, topFailureCount,
                trend);
    }

    private ConversationItem toItem(ChatEvaluationEntity entity) {
        String time = entity.getCreateDate() != null
                ? entity.getCreateDate().format(TIME_FMT)
                : "";
        return new ConversationItem(
                entity.getId(),
                entity.getQuestion(),
                entity.getAnswer(),
                entity.getModelId(),
                time,
                entity.getRating() != null ? entity.getRating() : "",
                entity.getFailureMode() != null ? entity.getFailureMode() : "",
                entity.getTraceId() != null ? entity.getTraceId() : "",
                parseSnippets(entity.getContextSnippets()),
                entity.getReference());
    }

    private List<ContextSnippet> parseSnippets(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(json,
                    new TypeReference<List<Map<String, Object>>>() {});
            return raw.stream()
                    .map(m -> new ContextSnippet(
                            (String) m.getOrDefault("text", ""),
                            toDouble(m.get("score"))))
                    .toList();
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse context snippets JSON", e);
            return List.of();
        }
    }

    private double toDouble(Object value) {
        if (value instanceof Number num) {
            return num.doubleValue();
        }
        return 0.0;
    }

    private long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        return 0L;
    }
}
