package net.topikachu.rag.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import net.topikachu.rag.autoevaluation.mapper.ChatEvaluationAutoMapper;
import net.topikachu.rag.business.document.entity.EtlJob;
import net.topikachu.rag.business.document.entity.EtlJobStatus;
import net.topikachu.rag.business.document.mapper.DocumentMapper;
import net.topikachu.rag.business.document.mapper.EtlJobMapper;
import net.topikachu.rag.business.document.mapper.KnowledgeParentBlockMapper;
import net.topikachu.rag.dto.DashboardStatsDTO;
import net.topikachu.rag.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardService {

    private final DocumentMapper documentMapper;
    private final KnowledgeParentBlockMapper knowledgeParentBlockMapper;
    private final SysUserMapper sysUserMapper;
    private final EtlJobMapper etlJobMapper;
    private final ChatEvaluationAutoMapper chatEvaluationAutoMapper;

    public DashboardStatsDTO getStats() {
        long totalDocuments = documentMapper.selectCount(new LambdaQueryWrapper<>());
        long totalChunks = knowledgeParentBlockMapper.selectCount(new LambdaQueryWrapper<>());
        long totalUsers = sysUserMapper.selectCount(new LambdaQueryWrapper<>());

        long totalJobs = etlJobMapper.selectCount(new LambdaQueryWrapper<>());
        long successJobs = etlJobMapper.selectCount(
                new LambdaQueryWrapper<EtlJob>().eq(EtlJob::getStatus, EtlJobStatus.SUCCESS.name()));
        long failedJobs = etlJobMapper.selectCount(
                new LambdaQueryWrapper<EtlJob>().eq(EtlJob::getStatus, EtlJobStatus.FAILED.name()));
        double successRate = totalJobs > 0
                ? Math.round((double) successJobs / totalJobs * 1000.0) / 10.0
                : 0.0;

        double satisfaction = computeSatisfaction();

        DashboardStatsDTO.EtlStatsDTO etlStats = DashboardStatsDTO.EtlStatsDTO.builder()
                .totalJobs(totalJobs)
                .successJobs(successJobs)
                .failedJobs(failedJobs)
                .successRate(successRate)
                .build();

        return DashboardStatsDTO.builder()
                .totalDocuments(totalDocuments)
                .totalChunks(totalChunks)
                .totalUsers(totalUsers)
                .satisfaction(satisfaction)
                .systemStatus("ONLINE")
                .etlStats(etlStats)
                .build();
    }

    private double computeSatisfaction() {
        Map<String, Object> summary = chatEvaluationAutoMapper.selectAutoSummary();
        if (summary == null || summary.isEmpty()) {
            return -1.0;
        }
        double avgFaithfulness = toDouble(summary.get("avg_faithfulness"));
        double avgAnswerRelevancy = toDouble(summary.get("avg_answer_relevancy"));
        double avgContextPrecision = toDouble(summary.get("avg_context_precision"));
        double avgContextRecall = toDouble(summary.get("avg_context_recall"));
        double avgAnswerCorrectness = toDouble(summary.get("avg_answer_correctness"));
        double avgAnswerSimilarity = toDouble(summary.get("avg_answer_similarity"));

        double overallAvg = (avgFaithfulness + avgAnswerRelevancy + avgContextPrecision
                + avgContextRecall + avgAnswerCorrectness + avgAnswerSimilarity) / 6.0;
        return Math.round(overallAvg * 1000.0) / 10.0;
    }

    private static double toDouble(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }
}
