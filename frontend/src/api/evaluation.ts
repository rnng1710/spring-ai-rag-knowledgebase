import { authFetch, apiUrl } from "./client";

// ——— Types (matches backend DTOs) ———

export interface ContextSnippet {
  text: string;
  score: number;
}

export interface EvalConversation {
  id: string;
  question: string;
  answer: string;
  model: string;
  time: string;
  rating: string | null;
  failureMode: string | null;
  traceId: string;
  contextSnippets: ContextSnippet[];
  reference: string | null;
}

export interface RagasTrendPoint {
  day: string;
  faithfulness: number | null;
  answerRelevancy: number | null;
  contextPrecision: number | null;
  contextRecall: number | null;
  answerCorrectness: number | null;
  answerSimilarity: number | null;
}

export interface EvaluationStats {
  total: number;
  approvalRate: number;
  approvalTrend: number;
  pending: number;
  topFailureMode: string | null;
  topFailureCount: number;
  trend: { day: string; rate: number }[];
}

export interface EvaluationPageResult {
  stats: EvaluationStats;
  items: EvalConversation[];
  total: number;
}

export interface RagasScoreItem {
  id: number;
  evaluationId: string;
  runId: string;
  faithfulness: number | null;
  answerRelevancy: number | null;
  contextPrecision: number | null;
  contextRecall: number | null;
  answerCorrectness: number | null;
  answerSimilarity: number | null;
  referenceAnswerHash: string;
  createDate: string;
}

export interface AutoRunItem {
  runId: string;
  status: "RUNNING" | "COMPLETED" | "FAILED" | string;
  totalSamples: number;
  successCount: number;
  failureCount: number;
  avgFaithfulness: number | null;
  avgAnswerRelevancy: number | null;
  avgContextPrecision: number | null;
  avgContextRecall: number | null;
  avgAnswerCorrectness: number | null;
  avgAnswerSimilarity: number | null;
  startedAt: string;
  completedAt: string | null;
  errorMessage: string | null;
}

export interface AutoStatsResult {
  lastRun: AutoRunItem | null;
  totalEvaluated: number;
  avgFaithfulness: number | null;
  avgAnswerRelevancy: number | null;
  avgContextPrecision: number | null;
  avgContextRecall: number | null;
  avgAnswerCorrectness: number | null;
  avgAnswerSimilarity: number | null;
  trend: RagasTrendPoint[];
}

interface AjaxEnvelope<T> {
  code: number;
  success: boolean;
  msg: string;
  data: T;
}

// ——— API Functions ———

export const submitFeedback = async (
  msgId: string,
  rating: "positive" | "negative",
  failureMode?: string,
): Promise<void> => {
  const response = await authFetch(
    apiUrl(`/api/v1/evaluation/${encodeURIComponent(msgId)}/feedback`),
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        rating,
        failureMode: failureMode || null,
      }),
    },
  );

  if (!response.ok) {
    throw new Error(`Feedback submission failed: ${response.status}`);
  }

  const json: AjaxEnvelope<null> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Feedback submission failed");
  }
};

export const saveReference = async (evaluationId: string, reference: string | null): Promise<void> => {
  const response = await authFetch(
    apiUrl(`/api/v1/admin/evaluation/${encodeURIComponent(evaluationId)}/reference`),
    {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reference: reference || null }),
    }
  );
  if (!response.ok) {
    throw new Error(`Failed to save reference: ${response.status}`);
  }
  const json: AjaxEnvelope<null> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Failed to save reference");
  }
};

export interface ListConversationsParams {
  page?: number;
  size?: number;
  modelId?: string;
  rating?: string;
  failureMode?: string;
  startDate?: string;
  endDate?: string;
}

export const listConversations = async (
  params: ListConversationsParams,
): Promise<EvaluationPageResult> => {
  const searchParams = new URLSearchParams();
  if (params.page) searchParams.set("page", String(params.page));
  if (params.size) searchParams.set("size", String(params.size));
  if (params.modelId) searchParams.set("modelId", params.modelId);
  if (params.rating) searchParams.set("rating", params.rating);
  if (params.failureMode) searchParams.set("failureMode", params.failureMode);
  if (params.startDate) searchParams.set("startDate", params.startDate);
  if (params.endDate) searchParams.set("endDate", params.endDate);

  const qs = searchParams.toString();
  const url = apiUrl("/api/v1/admin/evaluation/conversations" + (qs ? "?" + qs : ""));

  const response = await authFetch(url);

  if (!response.ok) {
    throw new Error(`Failed to fetch conversations: ${response.status}`);
  }

  const json: AjaxEnvelope<EvaluationPageResult> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Failed to fetch conversations");
  }
  return json.data;
};

export const triggerAutoEvaluation = async (): Promise<string> => {
  const response = await authFetch(apiUrl("/api/v1/admin/evaluation/auto/trigger"), {
    method: "POST",
  });
  if (!response.ok) {
    throw new Error(`Failed to trigger auto evaluation: ${response.status}`);
  }
  const json: AjaxEnvelope<string> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Failed to trigger auto evaluation");
  }
  return json.data;
};

export const fetchAutoStats = async (): Promise<AutoStatsResult> => {
  const response = await authFetch(apiUrl("/api/v1/admin/evaluation/auto/stats"));
  if (!response.ok) {
    throw new Error(`Failed to fetch auto stats: ${response.status}`);
  }
  const json: AjaxEnvelope<AutoStatsResult> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Failed to fetch auto stats");
  }
  return json.data;
};

export const fetchAutoScores = async (evaluationId: string): Promise<RagasScoreItem[]> => {
  const response = await authFetch(
    apiUrl(`/api/v1/admin/evaluation/auto/scores?evaluationId=${encodeURIComponent(evaluationId)}`),
  );
  if (!response.ok) {
    throw new Error(`Failed to fetch auto scores: ${response.status}`);
  }
  const json: AjaxEnvelope<RagasScoreItem[]> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Failed to fetch auto scores");
  }
  return json.data;
};

export const fetchAutoRuns = async (): Promise<AutoRunItem[]> => {
  const response = await authFetch(apiUrl("/api/v1/admin/evaluation/auto/runs"));
  if (!response.ok) {
    throw new Error(`Failed to fetch auto runs: ${response.status}`);
  }
  const json: AjaxEnvelope<AutoRunItem[]> = await response.json();
  if (json.code !== 0) {
    throw new Error(json.msg || "Failed to fetch auto runs");
  }
  return json.data;
};
