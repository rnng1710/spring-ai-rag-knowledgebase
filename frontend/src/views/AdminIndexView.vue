<template>
  <div class="page">
    <div class="header" style="margin-bottom: 24px;">
      <h2>{{ t("adminIndex.title") }}</h2>
    </div>

    <!-- 统计卡片区 -->
    <div class="stat-grid" style="margin-bottom: 24px;">
      <div class="stat-card card">
        <div class="stat-icon documents"><el-icon><Document /></el-icon></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalDocuments }}</div>
          <div class="stat-label">总文档数</div>
        </div>
      </div>
      <div class="stat-card card">
        <div class="stat-icon chunks"><el-icon><CopyDocument /></el-icon></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalChunks }}</div>
          <div class="stat-label">向量切片数</div>
        </div>
      </div>
      <div class="stat-card card">
        <div class="stat-icon users"><el-icon><User /></el-icon></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.totalUsers }}</div>
          <div class="stat-label">注册用户</div>
        </div>
      </div>
      <div class="stat-card card">
        <div class="stat-icon satisfaction"><el-icon><Star /></el-icon></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.satisfaction }}%</div>
          <div class="stat-label">用户满意度</div>
        </div>
      </div>
      <div class="stat-card card">
        <div class="stat-icon status" :class="{ 'is-offline': stats.systemStatus !== 'ONLINE' }"><el-icon><Monitor /></el-icon></div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.systemStatus === 'ONLINE' ? '运行中' : '异常' }}</div>
          <div class="stat-label">系统状态</div>
        </div>
      </div>
    </div>

    <!-- 主操作区 -->
    <el-row :gutter="20">
      <el-col :span="10">
        <div class="card operation-card">
          <div class="card-header">
            <h3>数据索引控制</h3>
            <span class="status-pill" :class="{ 'active': loading }">{{ status }}</span>
          </div>
          <div class="card-body">
            <p class="muted" style="margin-bottom: 30px; line-height: 1.6;">
              一键执行全量数据清洗与向量化处理。此操作将遍历所有未处理或失败的文档记录，拆分文本块并写入向量数据库。建议在系统负载较低时执行。
            </p>
            <el-button type="primary" size="large" @click="startIndexing" :loading="loading" class="full-width-btn">
              <el-icon class="el-icon--left"><RefreshRight /></el-icon>
              {{ t("adminIndex.runIndex") }}
            </el-button>
          </div>
        </div>
      </el-col>
      <el-col :span="14">
        <div class="card stream-card">
          <div class="card-header">
            <h3>{{ t("adminIndex.indexStream") }}</h3>
          </div>
          <div class="stream-box">{{ output || '等待任务开始...' }}</div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRouter } from "vue-router";
import { apiUrl } from "../api/client";
import { streamSsePost } from "../api/sse";
import { useI18n } from "vue-i18n";
import { Document, CopyDocument, User, Monitor, RefreshRight, Star } from '@element-plus/icons-vue';
import { getDashboardStats, DashboardStats } from "../api/dashboard";

const router = useRouter();
const { t } = useI18n();
const username = ref(localStorage.getItem("auth_user") || "admin");
const output = ref("");
const status = ref(t("adminIndex.idle"));
const loading = ref(false);

const stats = ref<DashboardStats>({
  totalDocuments: 0,
  totalChunks: 0,
  totalUsers: 0,
  satisfaction: 0,
  systemStatus: "ONLINE"
});

onMounted(async () => {
  try {
    stats.value = await getDashboardStats();
  } catch (e) {
    console.error("Failed to load dashboard stats", e);
  }
});

const startIndexing = async () => {
  output.value = "";
  status.value = t("adminIndex.running");
  loading.value = true;
  try {
    await streamSsePost(
      apiUrl("/api/v1/index"),
      {},
      (data) => {
        output.value += `${data}\n`;
      },
    );
    status.value = t("adminIndex.completed");
  } catch (err: any) {
    status.value = t("adminIndex.failed");
    output.value = `${t("adminIndex.errorPrefix")}: ${err?.message || err}`;
  } finally {
    loading.value = false;
  }
};
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 20px;
}
.stat-card {
  display: flex;
  align-items: center;
  padding: 24px;
  gap: 20px;
}
.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
}
.stat-icon.documents { background: rgba(64, 158, 255, 0.1); color: #409eff; }
.stat-icon.chunks { background: rgba(103, 194, 58, 0.1); color: #67c23a; }
.stat-icon.users { background: rgba(230, 162, 60, 0.1); color: #e6a23c; }
.stat-icon.satisfaction { background: rgba(245, 108, 108, 0.1); color: #f56c6c; }
.stat-icon.status { background: rgba(64, 158, 255, 0.1); color: #409eff; }
.stat-icon.status.is-offline { background: rgba(245, 108, 108, 0.1); color: #f56c6c; }

.stat-info {
  display: flex;
  flex-direction: column;
}
.stat-value {
  font-size: 28px;
  font-weight: 700;
  color: var(--chat-text-primary, #1e1e1e);
  line-height: 1.2;
}
.stat-label {
  font-size: 14px;
  color: var(--chat-text-secondary, #666);
  margin-top: 4px;
}

.operation-card {
  height: 100%;
  display: flex;
  flex-direction: column;
}
.stream-card {
  height: 100%;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}
.card-header h3 {
  margin: 0;
  font-size: 16px;
  color: var(--chat-text-primary, #1e1e1e);
}
.full-width-btn {
  width: 100%;
  border-radius: 8px;
}
.status-pill.active {
  background: var(--primary-color, #409eff);
  color: #fff;
}
</style>
