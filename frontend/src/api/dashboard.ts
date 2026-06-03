import { apiUrl, authFetch } from "./client";

export interface EtlStats {
  totalJobs: number;
  successJobs: number;
  failedJobs: number;
  successRate: number;
}

export interface DashboardStats {
  totalDocuments: number;
  totalChunks: number;
  totalUsers: number;
  satisfaction: number;
  systemStatus: "ONLINE" | "OFFLINE" | "DEGRADED";
  etlStats: EtlStats;
}

/**
 * 获取仪表盘大盘数据
 */
export const getDashboardStats = async (): Promise<DashboardStats> => {
  const res = await authFetch(apiUrl("/api/v1/dashboard/stats"));
  const json = await res.json();
  if (json.code === 0) {
    return json.data as DashboardStats;
  }
  throw new Error(json.msg || "获取仪表盘数据失败");
};
