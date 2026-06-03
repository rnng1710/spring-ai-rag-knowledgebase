<template>
  <div class="eval-page">
    <!-- Header -->
    <div class="eval-header">
      <h2 class="eval-title">{{ t("evaluation.title") }}</h2>
      <div class="eval-header-actions">
        <el-button size="small" type="primary" :loading="autoRunLoading" @click="runAutoEvaluation">
          {{ t("evaluation.runAuto") }}
        </el-button>
        <el-select v-model="filterModel" size="small" :placeholder="t('evaluation.filterModel')" style="width: 140px;" clearable>
          <el-option label="Qwen 2.5" value="ollama" />
          <el-option label="DeepSeek" value="deepseek" />
          <el-option label="Gemini" value="gemini" />
        </el-select>
        <el-date-picker
          v-model="filterDateRange"
          type="daterange"
          size="small"
          :start-placeholder="t('evaluation.startDate')"
          :end-placeholder="t('evaluation.endDate')"
          style="width: 240px;"
        />
      </div>
    </div>

    <!-- Empty State -->
    <div v-if="stats.total === 0" class="eval-empty">
      <div class="eval-empty-icon">
        <svg width="64" height="64" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1" stroke-linecap="round" stroke-linejoin="round" opacity="0.4">
          <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          <line x1="9" y1="10" x2="15" y2="10"/>
          <line x1="12" y1="7" x2="12" y2="13"/>
        </svg>
      </div>
      <p class="eval-empty-title">{{ t("evaluation.noDataTitle") }}</p>
      <p class="eval-empty-desc">{{ t("evaluation.noDataDesc") }}</p>
      <el-button type="primary" @click="goToChat">{{ t("evaluation.goChat") }}</el-button>
    </div>

    <!-- Content -->
    <template v-else>
      <!-- Stats Cards -->
      <div class="eval-metrics">
        <div class="eval-metric" style="flex: 0.8; border-left: 3px solid var(--ink);">
          <div class="metric-value" style="color: var(--ink)">{{ stats.total }}</div>
          <div class="metric-label">{{ t("evaluation.totalConversations") }}</div>
        </div>
        <div class="eval-metric" style="flex: 0.9;" :style="{ borderLeft: '3px solid ' + approvalColor }">
          <div class="metric-ring">
            <svg viewBox="0 0 36 36" class="metric-ring-svg">
              <circle cx="18" cy="18" r="14" fill="none" stroke="#e5e7eb" stroke-width="4" />
              <circle
                cx="18" cy="18" r="14" fill="none"
                :stroke="approvalColor"
                stroke-width="4"
                stroke-linecap="round"
                :stroke-dasharray="`${stats.approvalRate * 0.88} 88`"
                transform="rotate(-90 18 18)"
                style="transition: stroke-dasharray 0.8s ease, stroke 0.5s ease;"
              />
            </svg>
            <span class="metric-ring-text">{{ Math.round(stats.approvalRate) }}%</span>
          </div>
          <div class="metric-label">{{ t("evaluation.approvalRate") }}</div>
          <div class="metric-sub" :class="stats.approvalTrend >= 0 ? 'trend-up' : 'trend-down'">
            {{ stats.approvalTrend >= 0 ? '↑' : '↓' }} {{ Math.abs(stats.approvalTrend) }}% {{ t("evaluation.vsLastWeek") }}
          </div>
        </div>
        <div class="eval-metric" style="flex: 0.7;" :style="{ borderLeft: '3px solid ' + (stats.pending > 0 ? 'var(--eval-warning)' : 'var(--muted)') }">
          <div class="metric-value" :class="stats.pending > 0 ? 'metric-warn' : 'metric-muted'">{{ stats.pending }}</div>
          <div class="metric-label">{{ t("evaluation.pendingLabel") }}</div>
        </div>
        <div class="eval-metric" style="flex: 0.6; border-left: 3px solid var(--eval-red);">
          <div class="metric-value metric-danger">{{ stats.topFailureMode ? formatModeLabel(stats.topFailureMode) : '—' }}</div>
          <div class="metric-label">{{ t("evaluation.topFailureMode") }}</div>
          <div class="metric-sub muted" v-if="stats.topFailureCount">× {{ stats.topFailureCount }}</div>
        </div>
        <div class="eval-metric" style="flex: 1.8; border-left: 3px solid var(--eval-purple);">
          <div class="ragas-avg-grid">
            <div class="ragas-avg-item">
              <span class="ragas-avg-val">{{ formatAutoScore(autoStats.avgFaithfulness) }}</span>
              <span class="ragas-avg-lbl">{{ t("evaluation.faithfulness", "忠实度") }}</span>
            </div>
            <div class="ragas-avg-item">
              <span class="ragas-avg-val">{{ formatAutoScore(autoStats.avgAnswerRelevancy) }}</span>
              <span class="ragas-avg-lbl">{{ t("evaluation.answerRelevancy", "答案相关度") }}</span>
            </div>
            <div class="ragas-avg-item">
              <span class="ragas-avg-val">{{ formatAutoScore(autoStats.avgContextPrecision) }}</span>
              <span class="ragas-avg-lbl">{{ t("evaluation.contextPrecision", "上下文精确度") }}</span>
            </div>
            <div class="ragas-avg-item">
              <span class="ragas-avg-val">{{ formatAutoScore(autoStats.avgContextRecall) }}</span>
              <span class="ragas-avg-lbl">{{ t("evaluation.contextRecall", "上下文召回率") }}</span>
            </div>
            <div class="ragas-avg-item">
              <span class="ragas-avg-val">{{ formatAutoScore(autoStats.avgAnswerCorrectness) }}</span>
              <span class="ragas-avg-lbl">{{ t("evaluation.answerCorrectness", "答案正确性") }}</span>
            </div>
            <div class="ragas-avg-item">
              <span class="ragas-avg-val">{{ formatAutoScore(autoStats.avgAnswerSimilarity) }}</span>
              <span class="ragas-avg-lbl">{{ t("evaluation.answerSimilarity", "答案相似度") }}</span>
            </div>
          </div>
          <div class="metric-sub muted" style="margin-top: 8px;">{{ t("evaluation.autoEvaluated", { count: autoStats.totalEvaluated }) }}</div>
        </div>
      </div>

      <!-- Charts Row -->
      <div class="eval-charts">
        <div class="eval-chart" style="flex: 0.58;">
          <h4 class="chart-title">{{ t("evaluation.failureDistribution") }}</h4>
          <div class="bar-chart">
            <div
              v-for="(item, i) in sortedFailureModes"
              :key="item.mode"
              class="bar-row"
              :class="{ 'bar-row-clickable': item.count > 0 }"
              @click="item.count > 0 && toggleFilter(item.mode)"
            >
              <span
                class="bar-label"
                :class="{ 'bar-label-active': filterFailureMode === item.mode }"
              >{{ formatModeLabel(item.mode) }}</span>
              <div class="bar-track">
                <div
                  class="bar-fill"
                  :style="{
                    width: maxFailureCount > 0 ? (item.count / maxFailureCount * 100) + '%' : '0%',
                    background: barColor(i, sortedFailureModes.length)
                  }"
                ></div>
              </div>
              <span class="bar-count">{{ item.count }}</span>
            </div>
          </div>
        </div>
        <div class="eval-chart" style="flex: 0.42;">
          <h4 class="chart-title">{{ t("evaluation.approvalTrend") }}</h4>
          <div class="sparkline">
            <svg viewBox="0 0 240 60" preserveAspectRatio="none" class="sparkline-svg">
              <defs>
                <linearGradient id="sparkGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="#10b981" stop-opacity="0.3" />
                  <stop offset="100%" stop-color="#10b981" stop-opacity="0.02" />
                </linearGradient>
              </defs>
              <polygon
                :points="sparklineArea"
                fill="url(#sparkGrad)"
              />
              <polyline
                :points="sparklinePoints"
                fill="none"
                stroke="#10b981"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <div class="sparkline-labels">
              <span v-for="(d, i) in approvalTrendData" :key="i" class="sparkline-day">{{ d.day }}</span>
            </div>
          </div>
        </div>
        <div class="eval-chart" style="flex: 0.42;">
          <h4 class="chart-title">{{ t("evaluation.autoScoreTrend") }}</h4>
          <div class="sparkline">
            <svg viewBox="0 0 240 60" preserveAspectRatio="none" class="sparkline-svg">
              <defs>
                <linearGradient id="autoSparkGrad" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="#8b5cf6" stop-opacity="0.3" />
                  <stop offset="100%" stop-color="#8b5cf6" stop-opacity="0.02" />
                </linearGradient>
              </defs>
              <polygon :points="autoSparklineArea" fill="url(#autoSparkGrad)" />
              <polyline
                :points="autoSparklinePoints"
                fill="none"
                stroke="#8b5cf6"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
              />
            </svg>
            <div class="sparkline-labels">
              <span v-for="(d, i) in autoTrendData" :key="i" class="sparkline-day">{{ d.day }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Filters -->
      <div class="eval-filters">
        <el-select v-model="filterFailureMode" size="small" :placeholder="t('evaluation.filterByMode')" clearable style="width: 180px;">
          <el-option
            v-for="mode in allFailureModes"
            :key="mode.value"
            :label="formatModeLabel(mode.value)"
            :value="mode.value"
          />
        </el-select>
        <span class="filter-summary">{{ t("evaluation.showingRecords", { count: conversations.length, total: total }) }}</span>
      </div>

      <!-- Conversation List -->
      <el-table
        :data="pagedConversations"
        class="eval-table"
        row-class-name="eval-row"
        @expand-change="handleExpand"
        :row-key="rowKey"
      >
        <el-table-column type="expand" width="32">
          <template #default="{ row }">
            <div class="eval-detail">
              <div class="detail-section">
                <div class="detail-label">{{ t("evaluation.fullQuestion") }}</div>
                <div class="detail-text">{{ row.question }}</div>
              </div>
              <div class="detail-section">
                <div class="detail-label">{{ t("evaluation.fullAnswer") }}</div>
                <div class="detail-text">{{ row.answer }}</div>
              </div>
              <div class="detail-section">
                <div style="display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px;">
                  <div class="detail-label" style="margin-bottom: 0;">{{ t("evaluation.reference", "标准答案") }}</div>
                  <el-button v-if="!editingReference[row.id]" type="primary" link size="small" @click="startEditReference(row)" style="padding: 0;">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="margin-right: 4px;"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>
                    {{ t("common.edit", "编辑") }}
                  </el-button>
                </div>
                <div v-if="!editingReference[row.id]">
                  <div class="detail-text" :class="{'muted': !row.reference}">
                    {{ row.reference || t("evaluation.noReference", "暂无标准答案") }}
                  </div>
                </div>
                <div v-else>
                  <el-input v-model="referenceDraft[row.id]" type="textarea" :rows="3" placeholder="请输入标准答案..." />
                  <div style="margin-top: 8px; display: flex; gap: 8px;">
                    <el-button type="primary" size="small" :loading="savingReference[row.id]" @click="saveReferenceAction(row)">{{ t("common.save", "保存") }}</el-button>
                    <el-button size="small" @click="cancelEditReference(row)">{{ t("common.cancel", "取消") }}</el-button>
                    <el-button type="danger" link size="small" @click="clearReferenceAction(row)">{{ t("common.clear", "清空") }}</el-button>
                  </div>
                </div>
              </div>
              <div v-if="row.contextSnippets && row.contextSnippets.length > 0" class="detail-section">
                <div class="detail-label">{{ t("evaluation.retrievedSnippets", { total: row.contextSnippets.length }) }}</div>
                <div
                  v-for="(snippet, si) in row.contextSnippets"
                  :key="si"
                  class="detail-snippet"
                >
                  <div class="snippet-bar" :style="{ width: (snippet.score * 100) + '%', background: snippetScoreColor(snippet.score) }"></div>
                  <div class="snippet-text">{{ snippet.text }}</div>
                  <span class="snippet-score">{{ (snippet.score * 100).toFixed(0) }}</span>
                </div>
              </div>
              <div class="detail-section">
                <div class="detail-label">{{ t("evaluation.autoEvalDetail") }}</div>
                <div v-if="autoScoreLoading[row.id]" class="detail-text muted">{{ t("evaluation.loadingAutoScores") }}</div>
                <div v-else-if="autoScoresById[row.id]?.length" class="auto-score-grid">
                  <div
                    v-for="score in autoScoresById[row.id]"
                    :key="score.id"
                    class="auto-score-card"
                  >
                    <div class="auto-score-head">
                      <span class="auto-score-overall" style="font-size: 16px;">{{ t("evaluation.ragasRunId", "运行批次: ") }}{{ score.runId }}</span>
                      <span class="auto-score-model">{{ score.createDate?.slice(0,10) }}</span>
                    </div>
                    <div class="auto-score-dims">
                      <span>{{ t("evaluation.faithfulness", "忠实度") }}: {{ formatAutoScore(score.faithfulness) }}</span>
                      <span>{{ t("evaluation.answerRelevancy", "答案相关度") }}: {{ formatAutoScore(score.answerRelevancy) }}</span>
                      <span>{{ t("evaluation.contextPrecision", "上下文精确度") }}: {{ formatAutoScore(score.contextPrecision) }}</span>
                      <span>{{ t("evaluation.contextRecall", "上下文召回率") }}: {{ formatAutoScore(score.contextRecall) }}</span>
                      <span>{{ t("evaluation.answerCorrectness", "答案正确性") }}: {{ formatAutoScore(score.answerCorrectness) }}</span>
                      <span>{{ t("evaluation.answerSimilarity", "答案相似度") }}: {{ formatAutoScore(score.answerSimilarity) }}</span>
                    </div>
                    <div v-if="score.referenceAnswerHash === 'NO_REFERENCE'" class="auto-score-reason">
                      {{ t("evaluation.noReferenceUsed", "评估时未参考标准答案。") }}
                    </div>
                  </div>
                </div>
                <div v-else class="detail-text muted">{{ t("evaluation.noAutoScore") }}</div>
              </div>
              <div class="detail-actions">
                <div class="detail-field">
                  <span class="detail-label-sm">{{ t("evaluation.failureMode") }}</span>
                  <el-select v-model="row.failureMode" size="small" :placeholder="t('evaluation.selectMode')" style="width: 200px;" @change="(val: string) => updateFailureMode(row, val)">
                    <el-option
                      v-for="mode in allFailureModes"
                      :key="mode.value"
                      :label="formatModeLabel(mode.value)"
                      :value="mode.value"
                    />
                  </el-select>
                </div>
                <div class="detail-field">
                  <a v-if="row.traceId" :href="langfuseTraceUrl(row.traceId)" target="_blank" class="detail-trace-link">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6"/><polyline points="15 3 21 3 21 9"/><line x1="10" y1="14" x2="21" y2="3"/></svg>
                    Langfuse Trace
                  </a>
                </div>
                <div class="detail-rating">
                  <span
                    class="detail-thumb"
                    :class="{ active: row.rating === 'positive' }"
                    @click="row.rating = 'positive'; row.failureMode = ''"
                  >👍</span>
                  <span
                    class="detail-thumb"
                    :class="{ active: row.rating === 'negative' }"
                    @click="row.rating = 'negative'"
                  >👎</span>
                </div>
              </div>
            </div>
          </template>
        </el-table-column>
        <el-table-column :label="t('evaluation.question')" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="col-question" @click="toggleRowExpand(row)">{{ row.question }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('evaluation.answer')" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="col-answer">{{ row.answer }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('evaluation.model')" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" type="info">{{ row.model }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('evaluation.time')" width="140" align="center">
          <template #default="{ row }">
            <span class="col-time">{{ row.time }}</span>
          </template>
        </el-table-column>
        
        <el-table-column :label="t('evaluation.failureMode')" width="140" align="center">
          <template #default="{ row }">
            <span v-if="row.rating === 'positive'" class="mode-tag mode-positive">✓ {{ t("evaluation.good") }}</span>
            <span v-else-if="!row.failureMode" class="mode-tag mode-unclassified">— {{ t("evaluation.unclassified") }}</span>
            <span v-else class="mode-tag" :class="'mode-' + row.failureMode">{{ formatModeLabel(row.failureMode) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('evaluation.rating')" width="70" align="center">
          <template #default="{ row }">
            <span v-if="row.rating === 'positive'" style="color: var(--eval-success);">👍</span>
            <span v-else-if="row.rating === 'negative'" style="color: var(--eval-red);">👎</span>
            <span v-else style="color: var(--muted);">—</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- Pagination -->
      <div class="eval-pagination" v-if="total > pageSize">
        <el-pagination
          v-model:current-page="currentPage"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          size="small"
        />
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { ElMessage } from "element-plus";
import {
  fetchAutoScores,
  fetchAutoStats,
  listConversations,
  submitFeedback,
  triggerAutoEvaluation,
  saveReference
} from "../../api/evaluation";
import type { RagasScoreItem, AutoStatsResult, EvaluationStats, EvalConversation } from "../../api/evaluation";

const router = useRouter();
const { t } = useI18n();

// ——— Types ———





interface FailureModeItem {
  mode: string;
  count: number;
}

// ——— Filters ———

const filterModel = ref("");
const filterDateRange = ref<[Date, Date] | null>(null);
const filterFailureMode = ref("");
const currentPage = ref(1);
const pageSize = 10;

// ——— State ———

const stats = ref<EvaluationStats>({
  total: 0,
  approvalRate: 0,
  approvalTrend: 0,
  pending: 0,
  topFailureMode: "",
  topFailureCount: 0,
  trend: [],
});
const conversations = ref<EvalConversation[]>([]);
const total = ref(0);
const autoStats = ref<AutoStatsResult>({
  lastRun: null,
  totalEvaluated: 0,
  avgFaithfulness: null,
  avgAnswerRelevancy: null,
  avgContextPrecision: null,
  avgContextRecall: null,
  avgAnswerCorrectness: null,
  avgAnswerSimilarity: null,
  trend: [],
});
const autoRunLoading = ref(false);
const autoScoresById = ref<Record<string, RagasScoreItem[]>>({});
const autoScoreLoading = ref<Record<string, boolean>>({});
const editingReference = ref<Record<string, boolean>>({});
const referenceDraft = ref<Record<string, string>>({});
const savingReference = ref<Record<string, boolean>>({});

const startEditReference = (row: EvalConversation) => {
  referenceDraft.value[row.id] = row.reference || "";
  editingReference.value[row.id] = true;
};

const cancelEditReference = (row: EvalConversation) => {
  editingReference.value[row.id] = false;
};

const saveReferenceAction = async (row: EvalConversation) => {
  savingReference.value[row.id] = true;
  try {
    await saveReference(row.id, referenceDraft.value[row.id]);
    row.reference = referenceDraft.value[row.id];
    editingReference.value[row.id] = false;
    ElMessage.success(t("common.success", "Success"));
  } catch (e: any) {
    ElMessage.error(e?.message || "Failed");
  } finally {
    savingReference.value[row.id] = false;
  }
};

const clearReferenceAction = async (row: EvalConversation) => {
  savingReference.value[row.id] = true;
  try {
    await saveReference(row.id, null);
    row.reference = null;
    referenceDraft.value[row.id] = "";
    editingReference.value[row.id] = false;
    ElMessage.success(t("common.success", "Success"));
  } catch (e: any) {
    ElMessage.error(e?.message || "Failed");
  } finally {
    savingReference.value[row.id] = false;
  }
};

const ALL_FAILURE_MODES = [
  "short_text_rerank_bias",
  "synonym_mismatch",
  "chunk_boundary",
  "multi_document_gap",
  "irrelevant_retrieval",
  "generation_hallucination",
] as const;

const allFailureModes = ALL_FAILURE_MODES.map((m) => ({ value: m }));

const FAILURE_MODE_LABELS = computed((): Record<string, string> => ({
  short_text_rerank_bias: t("evalModes.shortTextRerankBias"),
  synonym_mismatch: t("evalModes.synonymMismatch"),
  chunk_boundary: t("evalModes.chunkBoundary"),
  multi_document_gap: t("evalModes.multiDocumentGap"),
  irrelevant_retrieval: t("evalModes.irrelevantRetrieval"),
  generation_hallucination: t("evalModes.generationHallucination"),
}));

const formatModeLabel = (mode: string) => FAILURE_MODE_LABELS.value[mode] || mode;

const formatDate = (d: Date | undefined): string | undefined => {
  if (!d) return undefined;
  // 仅截取日期部分（YYYY-MM-DD）：后端 API 接受日期字符串，去掉时间部分避免时区偏差
  return d.toISOString().slice(0, 10);
};

// ——— Data fetching ———

const fetchConversations = async () => {
  try {
    const result = await listConversations({
      page: currentPage.value,
      size: pageSize,
      modelId: filterModel.value || undefined,
      rating: undefined,
      failureMode: filterFailureMode.value || undefined,
      startDate: formatDate(filterDateRange.value?.[0] as Date | undefined),
      endDate: formatDate(filterDateRange.value?.[1] as Date | undefined),
    });
    conversations.value = result.items;
    stats.value = result.stats;
    total.value = result.total;
  } catch {
    // silently keep previous data
  }
};

const fetchAutoSummary = async () => {
  try {
    autoStats.value = await fetchAutoStats();
  } catch {
    // silently keep previous data
  }
};

onMounted(() => {
  fetchConversations();
  fetchAutoSummary();
});

// ——— Computed ———

const approvalColor = computed(() => {
  if (stats.value.approvalRate >= 70) return "var(--eval-success)";
  if (stats.value.approvalRate >= 40) return "var(--eval-warning)";
  return "var(--eval-danger)";
});

const failureModeCounts = computed(() => {
  const counts: Record<string, number> = {};
  ALL_FAILURE_MODES.forEach((m) => { counts[m] = 0; });
  conversations.value.forEach((c) => {
    if (c.failureMode && counts[c.failureMode] !== undefined) {
      counts[c.failureMode]++;
    }
  });
  return ALL_FAILURE_MODES.map((mode) => ({ mode, count: counts[mode] }));
});

const sortedFailureModes = computed(() =>
  [...failureModeCounts.value].sort((a, b) => b.count - a.count)
);

const maxFailureCount = computed(() =>
  Math.max(...sortedFailureModes.value.map((i) => i.count), 1)
);

const barColor = (index: number, total: number) => {
  const ratio = index / Math.max(total - 1, 1);
  const stops = [
    "var(--eval-red)",
    "var(--eval-orange)",
    "var(--eval-warning)",
    "var(--eval-blue)",
    "var(--eval-purple)",
    "var(--eval-cyan)",
  ];
  return stops[Math.min(index, stops.length - 1)];
};

const approvalTrendData = computed(() => stats.value.trend);

const sparklinePoints = computed(() => {
  const data = approvalTrendData.value;
  if (data.length === 0) return "";
  const w = 240;
  const h = 50;
  const pad = 8;
  const max = 100;
  const min = 0;
  return data
    .map((d, i) => {
      const x = pad + (i / Math.max(data.length - 1, 1)) * (w - pad * 2);
      const val = d.faithfulness || 0;
      const y = h - pad - ((val - min) / (max - min)) * (h - pad * 2);
      return `${x},${y}`;
    })
    .join(" ");
});

const sparklineArea = computed(() => {
  const pts = sparklinePoints.value;
  if (!pts) return "";
  return `8,50 ${pts} 232,50`;
});

const autoTrendData = computed(() => autoStats.value.trend);

const autoSparklinePoints = computed(() => {
  const data = autoTrendData.value;
  if (data.length === 0) return "";
  const w = 240;
  const h = 50;
  const pad = 8;
  const max = 100;
  const min = 0;
  return data
    .map((d, i) => {
      const x = pad + (i / Math.max(data.length - 1, 1)) * (w - pad * 2);
      const y = h - pad - ((d.rate - min) / (max - min)) * (h - pad * 2);
      return `${x},${y}`;
    })
    .join(" ");
});

const autoSparklineArea = computed(() => {
  const pts = autoSparklinePoints.value;
  if (!pts) return "";
  return `8,50 ${pts} 232,50`;
});

const pagedConversations = computed(() => conversations.value);

// ——— Methods ———

const rowKey = (row: EvalConversation) => row.id;

const toggleFilter = (mode: string) => {
  filterFailureMode.value = filterFailureMode.value === mode ? "" : mode;
  currentPage.value = 1;
};

const toggleRowExpand = (row: EvalConversation) => {
  // Toggle expand via table ref would be cleaner, but simple approach works
  currentPage.value = currentPage.value; // trigger reactivity
};

const handleExpand = (row: EvalConversation, expandedRows: EvalConversation[]) => {
  // 行展开时懒加载 RAGAS 评分：避免列表初始加载时批量请求所有行的评分数据
  if (expandedRows.some((item) => item.id === row.id) && !autoScoresById.value[row.id]) {
    loadAutoScores(row.id);
  }
};

const updateFailureMode = (row: EvalConversation, mode: string) => {
  row.failureMode = mode;
  if (mode && !row.rating) {
    row.rating = "negative";
  }
  submitFeedback(row.id, row.rating || "negative", mode || undefined).catch(() => {});
};

const snippetScoreColor = (score: number) => {
  if (score >= 0.7) return "var(--eval-success)";
  if (score >= 0.4) return "var(--eval-warning)";
  return "var(--eval-danger)";
};

const langfuseTraceUrl = (traceId: string) =>
  `http://192.168.193.128:3000/trace/${traceId}`;

const goToChat = () => {
  router.push("/user/chat");
};

const formatAutoScore = (score: number | null) => {
  return score === null || score === undefined ? "—" : score.toFixed(1);
};

const autoScoreClass = (score: number) => {
  if (score >= 4.0) return "auto-score-good";
  if (score >= 3.0) return "auto-score-warn";
  return "auto-score-bad";
};

const loadAutoScores = async (evaluationId: string) => {
  autoScoreLoading.value = { ...autoScoreLoading.value, [evaluationId]: true };
  try {
    const scores = await fetchAutoScores(evaluationId);
    // 展开运算符创建新对象：Vue 3 的 ref 需要整体替换才能触发响应式更新，直接修改属性不会触发
    autoScoresById.value = { ...autoScoresById.value, [evaluationId]: scores };
  } catch {
    autoScoresById.value = { ...autoScoresById.value, [evaluationId]: [] };
  } finally {
    autoScoreLoading.value = { ...autoScoreLoading.value, [evaluationId]: false };
  }
};

const runAutoEvaluation = async () => {
  autoRunLoading.value = true;
  try {
    const runId = await triggerAutoEvaluation();
    ElMessage.success(t("evaluation.runAutoStarted", { runId }));
    window.setTimeout(() => {
      fetchConversations();
      fetchAutoSummary();
    }, 5000);
  } catch (e: any) {
    ElMessage.error(e?.message || t("evaluation.runAutoFailed"));
  } finally {
    autoRunLoading.value = false;
  }
};

watch([filterFailureMode, filterModel, currentPage, filterDateRange], () => {
  fetchConversations();
});
</script>

<style scoped>
/* ——— Page ——— */
.eval-page {
  max-width: 1200px;
  margin: 0 auto;
}

.eval-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 24px;
}

.eval-title {
  font-size: 20px;
  font-weight: 700;
  color: var(--ink);
  margin: 0;
}

.eval-header-actions {
  display: flex;
  gap: 12px;
}

/* ——— Empty State ——— */
.eval-empty {
  text-align: center;
  padding: 80px 24px;
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 16px;
}

.eval-empty-icon {
  color: var(--muted);
  margin-bottom: 20px;
}

.eval-empty-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--ink);
  margin: 0 0 8px;
}

.eval-empty-desc {
  font-size: 14px;
  color: var(--muted);
  margin: 0 0 24px;
  max-width: 360px;
  margin-left: auto;
  margin-right: auto;
}

/* ——— Metrics ——— */
.eval-metrics {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}

.ragas-avg-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px 16px;
  width: 100%;
}
.ragas-avg-item {
  display: flex;
  flex-direction: column;
}
.ragas-avg-val {
  font-size: 20px;
  font-weight: 700;
  color: var(--eval-purple);
  line-height: 1.1;
}
.ragas-avg-lbl {
  font-size: 11px;
  color: var(--muted);
  white-space: nowrap;
}

.eval-metric {
  background: var(--card);
  border-radius: 14px;
  padding: 20px 24px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  box-shadow: 0 1px 3px rgba(15, 23, 42, 0.04);
}

.metric-value {
  font-size: 42px;
  font-weight: 700;
  line-height: 1.1;
  letter-spacing: -0.02em;
}

.metric-value.metric-warn { color: var(--eval-warning); }
.metric-value.metric-muted { color: var(--muted); }
.metric-value.metric-danger { color: var(--eval-red); font-size: 16px; font-weight: 600; }
.metric-value.metric-auto { color: var(--eval-purple); }

.metric-label {
  font-size: 13px;
  color: var(--muted);
  margin-top: 6px;
}

.metric-sub {
  font-size: 12px;
  margin-top: 4px;
}

.metric-sub.trend-up { color: var(--eval-success); }
.metric-sub.trend-down { color: var(--eval-red); }
.metric-sub.muted { color: var(--muted); }

.metric-ring {
  position: relative;
  width: 56px;
  height: 56px;
}

.metric-ring-svg {
  width: 100%;
  height: 100%;
}

.metric-ring-text {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 14px;
  font-weight: 700;
  color: var(--ink);
}

/* ——— Charts ——— */
.eval-charts {
  display: flex;
  gap: 16px;
  margin-bottom: 20px;
}

.eval-chart {
  background: var(--card);
  border: 1px solid var(--border);
  border-radius: 14px;
  padding: 20px 24px;
}

.chart-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
  margin: 0 0 16px;
}

/* Bar chart */
.bar-chart {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.bar-row {
  display: flex;
  align-items: center;
  gap: 10px;
}

.bar-row-clickable { cursor: pointer; }

.bar-label {
  width: 100px;
  font-size: 12px;
  color: var(--muted);
  text-align: right;
  flex-shrink: 0;
  transition: color 0.2s, font-weight 0.2s;
}

.bar-label-active {
  color: var(--ink);
  font-weight: 600;
}

.bar-track {
  flex: 1;
  height: 8px;
  background: #f1f3f4;
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.6s ease;
  min-width: 3px;
}

.bar-count {
  width: 24px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  text-align: right;
  flex-shrink: 0;
}

/* Sparkline */
.sparkline {
  width: 100%;
}

.sparkline-svg {
  width: 100%;
  height: 64px;
}

.sparkline-labels {
  display: flex;
  justify-content: space-between;
  padding: 0 4px;
  margin-top: 4px;
}

.sparkline-day {
  font-size: 11px;
  color: var(--muted);
}

/* ——— Filters ——— */
.eval-filters {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.filter-summary {
  font-size: 13px;
  color: var(--muted);
}

/* ——— Table ——— */
.eval-table {
  border-radius: 14px;
  overflow: hidden;
  border: 1px solid var(--border);
}

.eval-table :deep(.el-table__header th) {
  background: var(--chat-sidebar-bg, #fafbfc);
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  border-bottom: 1px solid var(--border);
}

.eval-table :deep(.el-table__body tr) {
  cursor: pointer;
}

.eval-table :deep(.el-table__body tr:hover > td) {
  background: var(--chat-action-hover, #f8faff);
}

.eval-table :deep(.el-table__expanded-cell) {
  background-color: var(--bg, #fafbfd) !important;
}

.col-question {
  font-weight: 500;
  color: var(--ink);
}

.col-answer {
  color: var(--muted);
  font-size: 13px;
}

.col-time {
  font-size: 12px;
  color: var(--muted);
}

.auto-score-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 38px;
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.auto-score-good {
  background: #ecfdf5;
  color: var(--eval-success);
}

.auto-score-warn {
  background: #fffbeb;
  color: var(--eval-warning);
}

.auto-score-bad {
  background: #fef2f2;
  color: var(--eval-red);
}

/* Failure mode tags */
.mode-tag {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}

.mode-positive {
  background: #ecfdf5;
  color: var(--eval-success);
}

.mode-unclassified {
  background: transparent;
  color: var(--muted);
  border: 1px dashed var(--border);
}

.mode-short_text_rerank_bias {
  background: #f5f3ff;
  color: var(--eval-purple);
}

.mode-synonym_mismatch {
  background: #fff7ed;
  color: var(--eval-orange);
}

.mode-chunk_boundary {
  background: #eff6ff;
  color: var(--eval-blue);
}

.mode-multi_document_gap {
  background: #ecfeff;
  color: var(--eval-cyan);
}

.mode-irrelevant_retrieval {
  background: #f8fafc;
  color: var(--eval-slate);
}

.mode-generation_hallucination {
  background: #fef2f2;
  color: var(--eval-red);
}

/* ——— Detail Panel ——— */
.eval-detail {
  padding: 16px 24px 20px;
  background: var(--bg, #fafbfd);
  border-top: 1px solid var(--border);
}

.detail-section {
  margin-bottom: 16px;
}

.detail-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 8px;
}

.detail-text {
  font-size: 14px;
  color: var(--text);
  line-height: 1.6;
  white-space: pre-wrap;
}

.detail-text.muted {
  color: var(--muted);
}

.auto-score-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 12px;
}

.auto-score-card {
  padding: 12px;
  background: var(--card, #fff);
  border: 1px solid var(--border);
  border-radius: 10px;
}

.auto-score-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.auto-score-overall {
  font-size: 24px;
  font-weight: 800;
  color: var(--eval-purple);
}

.auto-score-model {
  font-size: 12px;
  color: var(--muted);
}

.auto-score-dims {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 10px;
  font-size: 12px;
  color: var(--text);
}

.auto-score-reason {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--border);
  font-size: 13px;
  color: var(--muted);
  line-height: 1.5;
}

.detail-snippet {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 8px;
  padding: 8px 12px;
  background: var(--card, #fff);
  border: 1px solid var(--border);
  border-radius: 8px;
}

.snippet-bar {
  height: 4px;
  border-radius: 2px;
  margin-top: 8px;
  flex-shrink: 0;
  width: 40px !important;
  transition: background 0.3s;
}

.snippet-text {
  flex: 1;
  font-size: 13px;
  color: var(--text);
  line-height: 1.5;
}

.snippet-score {
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
  flex-shrink: 0;
}

.detail-actions {
  display: flex;
  align-items: center;
  gap: 24px;
  padding-top: 12px;
  border-top: 1px solid var(--border);
}

.detail-field {
  display: flex;
  align-items: center;
  gap: 8px;
}

.detail-label-sm {
  font-size: 12px;
  color: var(--muted);
  flex-shrink: 0;
}

.detail-trace-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--accent);
  text-decoration: none;
}

.detail-trace-link:hover {
  text-decoration: underline;
}

.detail-rating {
  display: flex;
  gap: 8px;
  margin-left: auto;
}

.detail-thumb {
  font-size: 18px;
  cursor: pointer;
  opacity: 0.35;
  transition: opacity 0.2s, transform 0.15s;
  user-select: none;
}

.detail-thumb:hover {
  opacity: 0.7;
  transform: scale(1.15);
}

.detail-thumb.active {
  opacity: 1;
}

/* ——— Pagination ——— */
.eval-pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
