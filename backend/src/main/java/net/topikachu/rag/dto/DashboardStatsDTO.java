package net.topikachu.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDTO {

    private long totalDocuments;
    private long totalChunks;
    private long totalUsers;
    private double satisfaction;
    private String systemStatus;
    private EtlStatsDTO etlStats;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class EtlStatsDTO {
        private long totalJobs;
        private long successJobs;
        private long failedJobs;
        private double successRate;
    }
}
