<template>
  <div class="chat-shell">
    <aside class="chat-sidebar">
      <div class="chat-sidebar-header">
        <div class="chat-title">{{ t("common.appName") }}</div>
        <div class="chat-sub">{{ t("chat.signedInAs", { username }) }}</div>
      </div>


      <el-button class="chat-action" @click="reset">
        <el-icon class="icon-margin"><Plus /></el-icon> {{ t("chat.newChat") }}
      </el-button>
      
      <div class="chat-history">
<!--        <div class="chat-history-title">Recent</div>-->
<!--        <div class="chat-history-item" @click="reset">New Conversation</div>-->
        <!-- Placeholder history items -->
      </div>
      
      <div style="margin-top:auto">
          <el-button text @click="logout" class="logout-btn">
            <el-icon class="icon-margin"><SwitchButton /></el-icon> {{ t("common.signOut") }}
          </el-button>
      </div>
    </aside>
    
    <main class="chat-main">
      
      <!-- Initial State: Centered Greeting -->
      <div v-if="messages.length === 0" class="chat-center-container">
          <div class="chat-greeting">
            <div class="greeting-text">{{ t("chat.hello", { username }) }}</div>
            <div class="greeting-sub">{{ t("chat.howCanIHelp") }}</div>
          </div>
      </div>

      <!-- Active State: Thread -->
      <div v-show="messages.length > 0" class="chat-thread" ref="chatThreadRef">
            <div v-for="(msg, index) in messages" :key="index" :class="['message-row', msg.role]">
                <div class="message-bubble">
                    
                    <!-- Assistant Icon (Left) -->
                    <!-- Assistant Icon (Left) -->
                    <div v-if="msg.role === 'assistant'" class="assistant-header">
                         <!-- Thinking State with Circle -->
                         <div v-if="!msg.content && index === messages.length - 1" class="thinking-wrapper">
                             <svg class="thinking-circle-svg" viewBox="25 25 50 50">
                                <defs>
                                    <linearGradient id="spinner-grad" x1="0%" y1="0%" x2="100%" y2="0%">
                                        <stop offset="0%" style="stop-color:#4285f4;stop-opacity:1" />
                                        <stop offset="50%" style="stop-color:#ea4335;stop-opacity:1" />
                                        <stop offset="100%" style="stop-color:#fbbc05;stop-opacity:1" />
                                    </linearGradient>
                                </defs>
                                <circle class="path" cx="50" cy="50" r="20" fill="none" stroke-width="4"></circle>
                             </svg>
                             <img src="https://www.gstatic.com/lamda/images/gemini_sparkle_v002_d4735304ff6292a690345.svg" class="thinking-icon" />
                         </div>
                         <img v-else 
                            src="https://www.gstatic.com/lamda/images/gemini_sparkle_v002_d4735304ff6292a690345.svg" 
                            width="24" height="24" 
                            class="assistant-icon"
                         />
                         
                         <span class="assistant-name">{{ msg.modelName || t("chat.assistant") }}</span>
                    </div>

                    <template v-if="msg.content">
                        <MarkdownRenderer v-if="msg.role === 'assistant'" :content="msg.content" class="message-content" />
                        <div v-else class="message-content formatted-content">{{ msg.content }}</div>
                    </template>
                    <div v-else class="message-content thinking-content">{{ t("chat.thinking") }}</div>

                    <div v-if="msg.followupOptions && msg.followupOptions.length > 0" class="followup-options">
                      <el-button
                        v-for="(option, optionIndex) in msg.followupOptions"
                        :key="`${msg.id}-followup-${optionIndex}`"
                        class="followup-option-btn"
                        plain
                        size="small"
                        :loading="!!msg.followupPending"
                        :disabled="!!msg.followupPending || loading"
                        @click="submitFollowupOption(msg.id, option)"
                      >
                        {{ option }}
                      </el-button>
                    </div>

                    <div v-if="msg.mode === 'agent' && msg.agentTrace" class="agent-trace-block">
                      <div class="agent-stage-line">
                        <span class="agent-stage-pill">{{ stageLabel(msg.agentTrace.currentStage) }}</span>
                        <span v-if="msg.agentTrace.isRevised" class="agent-revised-badge">{{ t("chat.revisedBadge") }}</span>
                      </div>
                      <details class="agent-trace-panel">
                        <summary>{{ t("chat.agentTrace") }}</summary>
                        <div class="agent-trace-log" v-for="(log, logIndex) in msg.agentTrace.logs" :key="`${msg.id}-${logIndex}`">
                          <span class="agent-log-stage">{{ stageLabel(log.stage) }}</span>
                          <span class="agent-log-text">{{ log.text }}</span>
                        </div>
                      </details>
                    </div>
                    
                    <!-- Sources -->
                    <div v-if="msg.sources && msg.sources.length > 0" class="message-sources">
                        <div v-for="(source, sIdx) in msg.sources" :key="sIdx" class="source-item" @click="openSource(source)">
                          {{ formatSourceReference(source) }}
                        </div>
                    </div>

                    <!-- Feedback -->
                    <div v-if="msg.role === 'assistant' && msg.content && msg.status === 'done'" class="message-feedback">
                      <span class="feedback-hint">{{ t("chat.feedbackHint") }}</span>
                      <span
                        class="feedback-thumb"
                        :class="{ active: msg.feedback === 'positive' }"
                        @click.stop="handlePositiveFeedback(msg)"
                      >👍</span>
                      <el-popover
                        :visible="feedbackPopoverId === msg.id"
                        placement="top"
                        :width="240"
                        trigger="click"
                        :show-arrow="false"
                        :offset="8"
                        popper-class="feedback-popover-popper"
                        @hide="feedbackPopoverId = null"
                      >
                        <template #reference>
                          <span
                            class="feedback-thumb"
                            :class="{ active: msg.feedback === 'negative' }"
                            @click.stop="feedbackPopoverId = msg.id"
                          >👎</span>
                        </template>
                        <div class="feedback-popover">
                          <p class="feedback-popover-title">{{ t("chat.feedbackPopoverTitle") }}</p>
                          <div
                            v-for="mode in FAILURE_MODES"
                            :key="mode.value"
                            class="feedback-mode-option"
                            @click="handleNegativeFeedback(msg, mode.value)"
                          >
                            {{ mode.label }}
                          </div>
                          <el-button size="small" text class="feedback-skip-btn" @click="handleNegativeFeedback(msg, ''); feedbackPopoverId = null">
                            {{ t("chat.feedbackSkip") }}
                          </el-button>
                        </div>
                      </el-popover>
                    </div>
                </div>
            </div>
          </div>
          
          <div class="chat-input-bottom">
            <div class="chat-input-container">
                <textarea 
                    v-model="question" 
                    class="chat-input-textarea" 
                  :placeholder="t('chat.askAnything')" 
                    rows="1"
                    @input="adjustTextareaHeight"
                    @keydown.enter.exact.prevent="startChat"
                ></textarea>
                <div class="chat-input-actions">
                  <div class="input-tip">{{ t("chat.inputTip") }}</div>
                    <div class="input-controls">
                         <ThemeToggle />
                     <el-tooltip :content="t('chat.clearChat')" placement="top">
                            <el-button circle size="small" @click="reset" :icon="Close" class="clear-btn" />
                         </el-tooltip>
                         <el-tooltip :content="t('chat.defaultSpaceHint')" placement="top">
                             <el-select v-model="selectedSpaces" :placeholder="t('chat.selectSpaces')" size="default" multiple clearable collapse-tags collapse-tags-tooltip @change="handleSpaceSelectionChange" class="space-select">
                                <template #prefix><el-icon><Collection /></el-icon></template>
                                <el-option v-for="space in spaceOptions" :key="space" :label="space" :value="space" />
                              </el-select>
                         </el-tooltip>
                         <el-select v-model="selectedMode" size="default" class="mode-select">
                             <el-option
                               v-for="mode in modeOptions"
                               :key="mode.value"
                               :label="mode.label"
                               :value="mode.value"
                             />
                         </el-select>
                         <el-select v-model="selectedModel" size="default" class="model-select">
                             <el-option label="Qwen 2.5" value="ollama" />
                             <el-option label="DeepSeek" value="deepseek" />
                             <el-option label="Gemini" value="gemini" />
                         </el-select>
                         <el-button type="primary" circle class="send-btn" @click="startChat" :loading="loading" :disabled="!question.trim()">
                            <el-icon><Position /></el-icon>
                         </el-button>
                    </div>
                </div>
            </div>
          </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onMounted } from "vue";
import { useRouter } from "vue-router";
import { apiUrl } from "../api/client";
import { streamSsePost } from "../api/sse";
import { listAccessibleSpaces, openDocPreview } from "../api/docs";
import { getMyPreferences, updateMyPreferences } from "../api/user";
import { submitFeedback } from "../api/evaluation";
import { ElMessage } from "element-plus";
import { Plus, SwitchButton, Position, Close, Collection } from "@element-plus/icons-vue";
import { clearAuthSession, getAccessToken, getUsernameFromAccessToken } from "../utils/auth";
import { useI18n } from "vue-i18n";
import MarkdownRenderer from "../components/MarkdownRenderer.vue";
import ThemeToggle from "../components/ThemeToggle.vue";

const router = useRouter();
const { t } = useI18n();
const username = ref(getUsernameFromAccessToken(getAccessToken()) || localStorage.getItem("auth_user") || "user");
const conversationId = ref(`conv-${Math.random().toString(36).slice(2, 8)}`);
const question = ref("");
const selectedSpaces = ref<string[]>([]);
const selectedModel = ref("ollama");
const selectedMode = ref<"rag" | "agent">("rag");
const modeOptions = computed(() => [
  { value: "rag", label: t("chat.modeFast") },
  { value: "agent", label: t("chat.modeAgent") },
]);

type ChatMode = "rag" | "agent";
type AgentStage =
  | "idle"
  | "planning"
  | "query_rewriting"
  | "retrieving"
  | "drafting"
  | "reviewing"
  | "revising"
  | "generating_final"
  | "followup"
  | "done"
  | "error";

interface SourceMeta {
  [key: string]: unknown;
  evidenceId?: string;
  docUuid?: string;
  fileName?: string;
  pageNumber?: number;
  fileType?: string;
  file_name?: string;
  source?: string;
  doc_uuid?: string;
  page_number?: number;
}

interface AgentTraceLog {
  stage: AgentStage;
  kind: "info" | "decision" | "critique" | "retrieval";
  text: string;
  timestamp: number;
}

interface AgentTrace {
  currentStage: AgentStage;
  logs: AgentTraceLog[];
  isRevised: boolean;
}

interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  mode: ChatMode;
  content: string;
  status: "pending" | "streaming" | "done" | "error";
  modelName?: string;
  sources?: SourceMeta[];
  agentTrace?: AgentTrace;
  followupOptions?: string[];
  followupPending?: boolean;
  feedback?: "positive" | "negative" | null;
  failureMode?: string;
}

type ChatAction =
  | { type: "START_REQUEST"; payload: { msgId: string; mode: ChatMode; modelName: string } }
  | { type: "STAGE_UPDATE"; payload: { msgId: string; stage: AgentStage } }
  | { type: "AGENT_NOTE_RECEIVED"; payload: { msgId: string; note: AgentTraceLog } }
  | { type: "SOURCES_RECEIVED"; payload: { msgId: string; sources: SourceMeta[] } }
  | { type: "FINAL_STREAM"; payload: { msgId: string; chunk: string } }
  | { type: "FOLLOWUP_RECEIVED"; payload: { msgId: string; text: string; options: string[] } }
  | { type: "REQUEST_COMPLETED"; payload: { msgId: string } }
  | { type: "REQUEST_FAILED"; payload: { msgId: string; message: string } }
  | { type: "ABORT"; payload: { msgId: string } }
  | { type: "RESET_CONVERSATION" };

const messages = ref<ChatMessage[]>([]);
const loading = ref(false);
const spaceOptions = ref<string[]>([]);
const activeMsgId = ref<string | null>(null);
const activeController = ref<AbortController | null>(null);
const feedbackPopoverId = ref<string | null>(null);

const FAILURE_MODES = computed(() => [
  { value: "short_text_rerank_bias", label: t("evalModes.shortTextRerankBias") },
  { value: "synonym_mismatch", label: t("evalModes.synonymMismatch") },
  { value: "chunk_boundary", label: t("evalModes.chunkBoundary") },
  { value: "multi_document_gap", label: t("evalModes.multiDocumentGap") },
  { value: "irrelevant_retrieval", label: t("evalModes.irrelevantRetrieval") },
  { value: "generation_hallucination", label: t("evalModes.generationHallucination") },
]);

const handlePositiveFeedback = (msg: ChatMessage) => {
  msg.feedback = "positive";
  msg.failureMode = undefined;
  submitFeedback(msg.id, "positive").catch(() => {});
};

const handleNegativeFeedback = (msg: ChatMessage, mode: string) => {
  msg.feedback = "negative";
  msg.failureMode = mode || undefined;
  feedbackPopoverId.value = null;
  submitFeedback(msg.id, "negative", mode || undefined).catch(() => {});
};

onMounted(async () => {
    try {
        spaceOptions.value = await listAccessibleSpaces();
        const preferences = await getMyPreferences();
        if (preferences.defaultSpaceCode && spaceOptions.value.includes(preferences.defaultSpaceCode)) {
          selectedSpaces.value = [preferences.defaultSpaceCode];
        } else if (spaceOptions.value.includes('public')) {
          selectedSpaces.value = ['public'];
          updateMyPreferences({ defaultSpaceCode: 'public' }).catch(console.error);
        } else {
          selectedSpaces.value = ['public'];
        }
    } catch (e) { console.error(e) }
});

const adjustTextareaHeight = (e: Event) => {
    const target = e.target as HTMLTextAreaElement;
    target.style.height = 'auto'; // Reset
    target.style.height = (target.scrollHeight) + 'px';
};

const scrollBottom = () => {
    nextTick(() => {
        const container = document.querySelector('.chat-thread');
        if (container) container.scrollTop = container.scrollHeight;
    });
};

// 格式化溯源引用标签：PDF 显示"文件名 · 第N页"，DOCX/MD 显示"文件名 · 面包屑路径"
const formatSourceReference = (source: any) => {
  const docUuid = source.doc_uuid || source.docUuid;
  const title = docUuid ? (source.file_name || source.fileName || source.source || t("chat.document")) : t("chat.unknownSource");
  const page = source.page_number || source.pageNumber;
  if (page) {
    if (isSegmentLocation(page)) {
      return t("chat.sourceReferenceWithSegment", { title, segment: String(page) });
    }
    return t("chat.sourceReferenceWithPage", { title, page: formatPageValue(page) });
  }
  return t("chat.sourceReferenceWithoutPage", { title });
};

// 点击溯源标签时打开原文预览：PDF 定位到对应页面，非 PDF 直接打开文件
const openSource = async (source: SourceMeta) => {
  const docUuid = source.doc_uuid || source.docUuid;
  if (!docUuid) return;
  try {
    const page = source.page_number ?? source.pageNumber;
    // 片段标签无法定位到具体页面，不带 page hash
    await openDocPreview(String(docUuid), isSegmentLocation(page) ? undefined : page);
  } catch (e) {
    console.error(e);
    ElMessage.error(t("chat.previewFailed"));
  }
};

const handleSpaceSelectionChange = async (values: string[]) => {
  if (!values || values.length === 0) {
    return;
  }
  try {
    await updateMyPreferences({ defaultSpaceCode: values[0] });
  } catch (e) {
    console.error(e);
  }
};

const formatPageValue = (page: unknown) => {
  if (typeof page === "number") {
    return Number.isInteger(page) ? String(page) : String(page);
  }
  if (typeof page === "string") {
    const numeric = Number(page);
    if (!Number.isNaN(numeric) && Number.isInteger(numeric)) {
      return String(numeric);
    }
  }
  return String(page);
};

// 判断溯源位置是否为"片段N"格式（非 PDF 文档的通用兜底标签）
const isSegmentLocation = (page: unknown) => {
  return typeof page === "string" && page.startsWith("片段");
};

const stageLabel = (stage: AgentStage) => {
  const labels: Record<AgentStage, string> = {
    idle: t("chat.stageIdle"),
    planning: t("chat.stagePlanning"),
    query_rewriting: t("chat.stageQueryRewriting"),
    retrieving: t("chat.stageRetrieving"),
    drafting: t("chat.stageDrafting"),
    reviewing: t("chat.stageReviewing"),
    revising: t("chat.stageRevising"),
    generating_final: t("chat.stageGeneratingFinal"),
    followup: t("chat.stageFollowup"),
    done: t("chat.stageDone"),
    error: t("chat.stageError"),
  };
  return labels[stage];
};

const dedupeSources = (rawSources: SourceMeta[]) => {
  const uniqueSources: SourceMeta[] = [];
  const seenFiles = new Set<string>();

  rawSources.forEach((s) => {
    const docUuid = String(s.doc_uuid || s.docUuid || s.file_name || s.fileName || s.source || "Unknown");
    const page = String(s.page_number ?? s.pageNumber ?? "");
    const key = `${docUuid}|${page}`;
    if (!seenFiles.has(key)) {
      seenFiles.add(key);
      uniqueSources.push(s);
    }
  });

  return uniqueSources;
};

const parseEventPayload = (data: string): unknown => {
  try {
    const parsed = JSON.parse(data);
    return parsed !== null && typeof parsed === "object" ? parsed : data;
  } catch {
    return data;
  }
};

const updateMessageById = (msgId: string, updater: (msg: ChatMessage) => void) => {
  const msg = messages.value.find((item) => item.id === msgId);
  if (msg) {
    updater(msg);
  }
};

// 集中式状态 reducer：所有聊天状态变更通过 dispatch(action) 统一管理，避免散落的响应式修改
const dispatch = (action: ChatAction) => {
  switch (action.type) {
    case "START_REQUEST":
      messages.value.push({
        id: action.payload.msgId,
        role: "assistant",
        mode: action.payload.mode,
        content: "",
        status: "pending",
        modelName: action.payload.modelName,
        sources: [],
        followupOptions: [],
        followupPending: false,
        agentTrace: action.payload.mode === "agent"
          ? { currentStage: "idle", logs: [], isRevised: false }
          : undefined,
      });
      return;
    case "STAGE_UPDATE":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.agentTrace ??= { currentStage: "idle", logs: [], isRevised: false };
        msg.agentTrace.currentStage = action.payload.stage;
        if (action.payload.stage === "revising") {
          msg.agentTrace.isRevised = true;
        }
      });
      return;
    case "AGENT_NOTE_RECEIVED":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.agentTrace ??= { currentStage: "idle", logs: [], isRevised: false };
        msg.agentTrace.logs.push(action.payload.note);
      });
      return;
    case "SOURCES_RECEIVED":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.sources = dedupeSources(action.payload.sources);
      });
      return;
    case "FINAL_STREAM":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.status = "streaming";
        msg.content += action.payload.chunk;
        msg.followupOptions = [];
        msg.followupPending = false;
      });
      return;
    case "FOLLOWUP_RECEIVED":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.status = "done";
        msg.content = action.payload.text;
        msg.followupOptions = action.payload.options;
        msg.followupPending = false;
        if (msg.agentTrace) {
          msg.agentTrace.currentStage = "followup";
        }
      });
      return;
    case "REQUEST_COMPLETED":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.status = "done";
        if (msg.agentTrace) {
          msg.agentTrace.currentStage = "done";
        }
      });
      return;
    case "REQUEST_FAILED":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.status = "error";
        msg.content = msg.content || `[${t("chat.errorPrefix")}: ${action.payload.message}]`;
        msg.followupPending = false;
        if (msg.agentTrace) {
          msg.agentTrace.currentStage = "error";
        }
      });
      return;
    case "ABORT":
      updateMessageById(action.payload.msgId, (msg) => {
        msg.status = "done";
      });
      return;
    case "RESET_CONVERSATION":
      messages.value = [];
      return;
  }
};

const setFollowupPending = (msgId: string, pending: boolean) => {
  updateMessageById(msgId, (msg) => {
    msg.followupPending = pending;
  });
};

const submitChat = async (userInput: string, options?: { clearInput?: boolean; followupMsgId?: string }) => {
  const trimmedInput = userInput.trim();
  if (!trimmedInput) return;

  if (options?.clearInput) {
    question.value = "";
  }
  if (options?.followupMsgId) {
    setFollowupPending(options.followupMsgId, true);
  }

  // 后端 ollama 默认部署 Qwen 2.5 模型，后续换模型时需同步更新此映射
  const modelNameMap: Record<string, string> = {
    ollama: 'Qwen 2.5',
    deepseek: 'DeepSeek',
    gemini: 'Gemini',
  };
  const currentModelName = modelNameMap[selectedModel.value] || selectedModel.value;
  const msgId = `msg-${Math.random().toString(36).slice(2, 10)}`;
  activeMsgId.value = msgId;
  
  // Add User Message
  messages.value.push({ id: `${msgId}-user`, role: 'user', mode: selectedMode.value, content: trimmedInput, status: "done" });
  scrollBottom();
  
  dispatch({ type: "START_REQUEST", payload: { msgId, mode: selectedMode.value, modelName: currentModelName } });
  scrollBottom();
  
  loading.value = true;
  const controller = new AbortController();
  activeController.value = controller;
  try {
    await streamSsePost(
      apiUrl(`/api/v1/chat?conversationId=${encodeURIComponent(conversationId.value)}`),
      {
        userInput: trimmedInput,
        spaceCodes: selectedSpaces.value,
        modelId: selectedModel.value,
        mode: selectedMode.value,
        msgId,
      },
      (event, data) => {
        const payload = parseEventPayload(data);
        if (event === "agent_stage" && typeof payload === "object" && payload !== null) {
          const stagePayload = payload as { msgId?: string; stage?: AgentStage };
          if (stagePayload.msgId && stagePayload.stage) {
            dispatch({ type: "STAGE_UPDATE", payload: { msgId: stagePayload.msgId, stage: stagePayload.stage } });
          }
        } else if (event === "agent_note" && typeof payload === "object" && payload !== null) {
          const notePayload = payload as { msgId?: string; stage?: AgentStage; kind?: AgentTraceLog["kind"]; text?: string; timestamp?: number };
          if (notePayload.msgId && notePayload.stage && notePayload.kind && notePayload.text) {
            dispatch({
              type: "AGENT_NOTE_RECEIVED",
              payload: {
                msgId: notePayload.msgId,
                note: {
                  stage: notePayload.stage,
                  kind: notePayload.kind,
                  text: notePayload.text,
                  timestamp: notePayload.timestamp || Date.now(),
                },
              },
            });
          }
        } else if (event === "sources") {
          if (Array.isArray(payload)) {
            dispatch({ type: "SOURCES_RECEIVED", payload: { msgId, sources: payload as SourceMeta[] } });
          } else if (typeof payload === "object" && payload !== null) {
            const sourcePayload = payload as { msgId?: string; sources?: SourceMeta[] };
            if (sourcePayload.msgId && Array.isArray(sourcePayload.sources)) {
              dispatch({ type: "SOURCES_RECEIVED", payload: { msgId: sourcePayload.msgId, sources: sourcePayload.sources } });
            }
          }
        } else if (event === "message") {
          if (typeof payload === "string") {
            dispatch({ type: "FINAL_STREAM", payload: { msgId, chunk: payload } });
          } else if (typeof payload === "object" && payload !== null) {
            const messagePayload = payload as { msgId?: string; chunk?: string };
            if (messagePayload.msgId && messagePayload.chunk) {
              dispatch({ type: "FINAL_STREAM", payload: { msgId: messagePayload.msgId, chunk: messagePayload.chunk } });
            }
          }
          scrollBottom();
        } else if (event === "followup" && typeof payload === "object" && payload !== null) {
          const followupPayload = payload as { msgId?: string; text?: string; options?: string[] };
          if (followupPayload.msgId && followupPayload.text && Array.isArray(followupPayload.options)) {
            dispatch({
              type: "FOLLOWUP_RECEIVED",
              payload: {
                msgId: followupPayload.msgId,
                text: followupPayload.text,
                options: followupPayload.options,
              },
            });
            scrollBottom();
          }
        } else if (event === "done" && typeof payload === "object" && payload !== null) {
          const donePayload = payload as { msgId?: string };
          if (donePayload.msgId) {
            dispatch({ type: "REQUEST_COMPLETED", payload: { msgId: donePayload.msgId } });
          }
        } else if (event === "error" && typeof payload === "object" && payload !== null) {
          const errorPayload = payload as { msgId?: string; message?: string };
          if (errorPayload.msgId && errorPayload.message) {
            dispatch({ type: "REQUEST_FAILED", payload: { msgId: errorPayload.msgId, message: errorPayload.message } });
          }
        }
      },
      controller.signal
    );
  } catch (err: any) {
    if (err?.name === "AbortError") {
      dispatch({ type: "ABORT", payload: { msgId } });
    } else {
      dispatch({ type: "REQUEST_FAILED", payload: { msgId, message: err?.message || String(err) } });
    }
  } finally {
    loading.value = false;
    activeController.value = null;
    activeMsgId.value = null;
    if (options?.followupMsgId) {
      setFollowupPending(options.followupMsgId, false);
    }
  }
};

const startChat = async () => {
  if(!question.value.trim()) return;
  await submitChat(question.value, { clearInput: true });
};

const submitFollowupOption = async (followupMsgId: string, option: string) => {
  if (loading.value) return;
  await submitChat(option, { followupMsgId, clearInput: false });
};

const reset = () => {
  if (activeController.value && activeMsgId.value) {
    activeController.value.abort();
    dispatch({ type: "ABORT", payload: { msgId: activeMsgId.value } });
  }
  dispatch({ type: "RESET_CONVERSATION" });
  question.value = "";
  conversationId.value = `conv-${Math.random().toString(36).slice(2, 8)}`;
};

const logout = () => {
  clearAuthSession();
  router.push("/login"); 
};
</script>

<style scoped>
/* --- Extracted Inline Styles --- */
.kb-selector-title {
  font-size: 12px;
  color: var(--chat-text-secondary);
  margin-bottom: 8px;
  font-weight: 500;
}
.kb-selector-hint {
  font-size: 12px;
  color: var(--muted);
  margin-top: 8px;
}
.icon-margin {
  margin-right: 8px;
}
.logout-btn {
  width: 100%;
  justify-content: flex-start;
  color: var(--chat-text-secondary);
}
.space-select {
  width: 200px;
  flex-shrink: 0;
}
.model-select {
  width: 140px;
  flex-shrink: 0;
}
.mode-select {
  width: 120px;
  flex-shrink: 0;
}
.assistant-header {
  display: flex;
  align-items: flex-start;
  margin-bottom: 8px;
}
.assistant-icon {
  margin-right: 12px;
}
.assistant-name {
  font-weight: 600;
  font-size: 14px;
  margin-top: 2px;
  color: var(--chat-text-primary);
}
.formatted-content {
  white-space: pre-wrap;
  color: var(--chat-text-primary);
}
.thinking-content {
  color: var(--muted);
  font-style: italic;
}
.source-item {
  font-size: 13px;
  color: var(--chat-text-secondary);
  margin-top: 4px;
}
.clear-btn {
  border: none;
  background: transparent;
  color: var(--chat-text-primary);
}

/* --- Gemini-like Layout --- */
/* Note: .chat-shell, .chat-sidebar, .chat-main are now inherited from base.css */

/* Sidebar */
.chat-sidebar-header {
  padding: 0 12px;
  margin-bottom: 0px;
}
.chat-title {
  font-size: 20px;
  font-weight: 500;
  color: var(--chat-text-primary);
}
.chat-sub {
  font-size: 12px;
  color: var(--chat-text-secondary);
  margin-top: 4px;
}

.chat-action {
  width: 100%;
  justify-content: flex-start;
  margin-bottom: 8px;
  border: none;
  background-color: var(--chat-action-bg);
  color: var(--chat-text-primary);
  border-radius: 20px; 
  font-weight: 500;
  padding: 20px 16px; 
}
.chat-action:hover {
  background-color: var(--chat-action-hover);
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  margin-top: 10px;
}
.chat-history-title {
  font-size: 12px;
  font-weight: 500;
  color: var(--chat-text-secondary);
  margin-bottom: 8px;
  padding-left: 12px;
}
.chat-history-item {
  padding: 10px 16px;
  border-radius: 18px;
  font-size: 14px;
  color: var(--chat-text-secondary);
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.2s;
}
.chat-history-item:hover {
  background-color: var(--chat-action-bg);
}

/* --- Initial State --- */
.chat-center-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding-bottom: 10vh; /* Visual optical center */
}

.chat-greeting {
    text-align: center;
    margin-bottom: 40px;
}
.greeting-text {
  font-size: 44px;
  font-weight: 500;
  background: -webkit-linear-gradient(45deg, #4285f4, #9b72cb);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  letter-spacing: -1px;
}
.greeting-sub {
  font-size: 24px;
  color: var(--muted);
  margin-top: 8px;
}

.chat-input-center-wrapper {
    width: 100%;
    max-width: 800px;
    padding: 0 20px;
}

/* --- Active State --- */
.chat-thread {
  flex: 1;
  overflow-y: auto;
  padding: 40px 0 100px 0; /* Box bottom padding for input space */
  display: flex;
  flex-direction: column;
  align-items: center; 
  scroll-behavior: smooth;
  background: transparent;
  border: none;
  box-shadow: none;
}

.chat-input-bottom {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 20px;
  padding-bottom: 30px;
  background: linear-gradient(180deg, var(--chat-thread-bg) 0%, var(--chat-thread-bg) 40%);
  display: flex;
  justify-content: center;
  mask-image: linear-gradient(to bottom, transparent, black 10%);
  -webkit-mask-image: linear-gradient(to bottom, transparent, black 10%);
}

/* --- Reusable Components --- */

.chat-input-container {
    width: 100%;
    max-width: 800px;
    background-color: var(--chat-input-bg);
    border-radius: 30px;
    padding: 16px 24px; 
    display: flex;
    flex-direction: column;
    transition: background-color 0.2s;
}
.chat-input-container:focus-within {
    background-color: var(--chat-input-focus);
}

.chat-input-textarea {
    width: 100%;
    border: none;
    background: transparent;
    resize: none;
    outline: none;
    font-family: inherit;
    font-size: 16px;
    line-height: 1.5;
    color: var(--chat-text-primary);
    min-height: 24px;
    max-height: 200px;
}

.chat-input-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-top: 12px;
    gap: 12px;
}
.input-tip {
    font-size: 12px;
    color: var(--chat-text-secondary);
}
.input-controls {
    display: flex;
    gap: 10px;
    align-items: center;
}

@media (max-width: 900px) {
  .chat-sidebar {
    display: none;
  }
  .greeting-text {
    font-size: 32px;
  }
}

@media (max-width: 720px) {
  .chat-input-actions {
    flex-direction: column;
    align-items: stretch;
  }

  .input-controls {
    width: 100%;
    justify-content: flex-end;
    flex-wrap: wrap;
  }

  .mode-select {
    width: 130px;
    flex-shrink: 0;
  }
}

/* Messages */
.message-row {
  width: 100%;
  max-width: 800px;
  padding: 0 24px;
  display: flex;
  margin-bottom: 32px;
}

.message-row.user {
  justify-content: flex-end;
}
.message-row.user .message-bubble {
  background-color: var(--chat-user-bubble);
  color: var(--chat-text-primary);
  border-radius: 20px;
  padding: 14px 20px;
  max-width: 80%;
}

.message-row.assistant {
  justify-content: flex-start;
}
@keyframes rotate {
  100% { transform: rotate(360deg); }
}

@keyframes dash {
  0% {
    stroke-dasharray: 1, 200;
    stroke-dashoffset: 0;
  }
  50% {
    stroke-dasharray: 89, 200;
    stroke-dashoffset: -35px;
  }
  100% {
    stroke-dasharray: 89, 200;
    stroke-dashoffset: -124px;
  }
}

.thinking-wrapper {
  position: relative;
  width: 32px; /* Slightly larger wrapper for the ring */
  height: 32px;
  margin-right: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.thinking-circle-svg {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  animation: rotate 2s linear infinite;
  transform-origin: center center;
}

.thinking-circle-svg .path {
  stroke-dasharray: 1, 200;
  stroke-dashoffset: 0;
  animation: dash 1.5s ease-in-out infinite;
  stroke-linecap: round;
  stroke: url(#spinner-grad); /* Reference the gradient ID in template */
}

.thinking-icon {
  z-index: 1;
  width: 20px;
  height: 20px;
}

.message-row.assistant .message-bubble {
  background-color: var(--chat-agent-bubble);
  color: var(--chat-text-primary);
  border-radius: 0;
  padding: 0; 
  padding-right: 20px;
  max-width: 100%;
  line-height: 1.6;
  font-size: 16px;
}

.message-sources {
    margin-top: 16px;
    padding-top: 0;
    border-top: 1px solid var(--chat-sidebar-border); /* Softer border */
}
.agent-trace-block {
    margin-top: 14px;
}
.agent-stage-line {
    display: flex;
    gap: 8px;
    align-items: center;
    flex-wrap: wrap;
}
.agent-stage-pill,
.agent-revised-badge {
    display: inline-flex;
    align-items: center;
    border-radius: 999px;
    padding: 4px 10px;
    font-size: 12px;
    font-weight: 500;
}
.agent-stage-pill {
    background: var(--chat-pill-bg);
    color: var(--chat-pill-text);
}
.agent-revised-badge {
    background: var(--chat-revised-bg);
    color: var(--chat-revised-text);
}
.agent-trace-panel {
    margin-top: 10px;
    background: var(--chat-trace-bg);
    border: 1px solid var(--chat-trace-border);
    border-radius: 14px;
    padding: 10px 14px;
}
.agent-trace-panel summary {
    cursor: pointer;
    font-size: 13px;
    color: var(--chat-text-secondary);
    font-weight: 500;
}
.agent-trace-log {
    display: flex;
    gap: 8px;
    margin-top: 10px;
    font-size: 13px;
    line-height: 1.5;
}
.agent-log-stage {
    flex: 0 0 auto;
    color: var(--chat-pill-text);
    font-weight: 600;
}
.agent-log-text {
    color: var(--chat-text-secondary);
}
.followup-options {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 14px;
}
.followup-option-btn {
    max-width: 100%;
    white-space: normal;
    text-align: left;
    height: auto;
    line-height: 1.5;
}
.sources-title {
    font-size: 13px;
    font-weight: 500;
    color: var(--chat-text-secondary);
    margin-bottom: 8px;
}
.sources-list {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
}
.source-tag {
    cursor: pointer;
}

/* Feedback */
.message-feedback {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--chat-sidebar-border);
}

.feedback-hint {
  font-size: 12px;
  color: var(--muted);
  margin-right: 4px;
}

.feedback-thumb {
  font-size: 16px;
  cursor: pointer;
  opacity: 0.35;
  transition: opacity 0.2s, transform 0.15s;
  user-select: none;
  padding: 2px;
}

.feedback-thumb:hover {
  opacity: 0.7;
  transform: scale(1.15);
}

.feedback-thumb.active {
  opacity: 1;
}

.feedback-popover-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  margin: 0 0 10px;
}

.feedback-mode-option {
  padding: 8px 12px;
  font-size: 13px;
  color: var(--text);
  cursor: pointer;
  border-radius: 8px;
  transition: background 0.15s;
}

.feedback-mode-option:hover {
  background: var(--chat-input-focus);
}

.feedback-skip-btn {
  margin-top: 8px;
  width: 100%;
  color: var(--muted);
}
</style>

<style>
.feedback-popover-popper {
  padding: 12px 0 !important;
  border-radius: 14px !important;
  box-shadow: 0 12px 32px rgba(15, 23, 42, 0.12) !important;
}
</style>
