import { apiClient } from "./client";

export interface DashboardStats {
  totalDocuments: number;
  totalChunks: number;
  totalUsers: number;
  satisfaction: number;
  systemStatus: "ONLINE" | "OFFLINE" | "DEGRADED";
}

/**
 * 获取仪表盘大盘数据
 * 注意：当前为了前端展示使用了 Mock 数据。
 * 真实后端需要实现: GET /api/v1/dashboard/stats
 */
export const getDashboardStats = async (): Promise<DashboardStats> => {
  // TODO: 当后端接口实现后，取消下面两行的注释，并删除模拟数据
  // const response = await apiClient.get<DashboardStats>("/api/v1/dashboard/stats");
  // return response.data;
  
  return new Promise((resolve) => {
    setTimeout(() => {
      resolve({
        totalDocuments: 128,
        totalChunks: 3450,
        totalUsers: 24,
        satisfaction: 98,
        systemStatus: "ONLINE"
      });
    }, 500); // 模拟网络延迟
  });
};
