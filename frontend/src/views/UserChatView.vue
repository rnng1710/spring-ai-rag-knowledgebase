<template>
  <div class="chat-shell">
    <aside class="chat-sidebar">
      <div class="chat-sidebar-header">
        <div class="chat-title">{{ t("common.appName") }}</div>
        <div class="chat-sub">{{ t("chat.signedInAs", { username }) }}</div>
      </div>
      
      <!-- Space Scope Selector -->
      <div class="kb-selector" style="margin-bottom: 20px; padding: 0 12px;">
         <div style="font-size:12px; color:#444746; margin-bottom:8px; font-weight:500">{{ t("chat.spaceScope") }}</div>
         <el-select v-model="selectedSpaces" :placeholder="t('chat.selectSpaces')" size="small" multiple clearable collapse-tags collapse-tags-tooltip>
            <template #prefix><el-icon><Collection /></el-icon></template>
            <el-option v-for="space in spaceOptions" :key="space" :label="space" :value="space" />
          </el-select>
         <div style="font-size:12px; color:#777; margin-top:8px;">{{ t("chat.allAccessibleSpaces") }}</div>
      </div>

      <el-button class="chat-action" @click="reset">
        <el-icon style="margin-right:8px"><Plus /></el-icon> {{ t("chat.newChat") }}
      </el-button>
      
      <div class="chat-history">
<!--        <div class="chat-history-title">Recent</div>-->
<!--        <div class="chat-history-item" @click="reset">New Conversation</div>-->
        <!-- Placeholder history items -->
      </div>
      
      <div style="margin-top:auto">
          <el-button text @click="logout" style="width:100%; justify-content:flex-start; color:#444746">
            <el-icon style="margin-right:8px"><SwitchButton /></el-icon> {{ t("common.signOut") }}
          </el-button>
      </div>
    </aside>
    
    <main class="chat-main" :class="{ 'is-centered-mode': messages.length === 0 }">
      
      <!-- Initial State: Centered Greeting & Input -->
      <div v-if="messages.length === 0" class="chat-center-container">
          <div class="chat-greeting">
            <div class="greeting-text">{{ t("chat.hello", { username }) }}</div>
            <div class="greeting-sub">{{ t("chat.howCanIHelp") }}</div>
          </div>
          
          <div class="chat-input-center-wrapper">
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
                         <el-select v-model="selectedMode" size="small" class="mode-select">
                             <el-option
                               v-for="mode in modeOptions"
                               :key="mode.value"
                               :label="mode.label"
                               :value="mode.value"
                             />
                         </el-select>
                         <el-select v-model="selectedModel" size="small" style="width: 120px;">
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
      </div>

      <!-- Active State: Thread + Bottom Input -->
      <template v-else>
          <div class="chat-thread">
            <div v-for="(msg, index) in messages" :key="index" :class="['message-row', msg.role]">
                <div class="message-bubble">
                    
                    <!-- Assistant Icon (Left) -->
                    <!-- Assistant Icon (Left) -->
                    <div v-if="msg.role === 'assistant'" style="display:flex; align-items:flex-start; margin-bottom: 8px;">
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
                         <!-- Normal State -->
                         <img v-else 
                            src="https://www.gstatic.com/lamda/images/gemini_sparkle_v002_d4735304ff6292a690345.svg" 
                            width="24" height="24" 
                            style="margin-right:12px"
                         />
                         
                         <span style="font-weight:600; font-size:14px; margin-top: 2px;">{{ msg.modelName || t("chat.assistant") }}</span>
                    </div>

                    <div v-if="msg.content" class="message-content" style="white-space: pre-wrap;">{{ msg.content }}</div>
                      <div v-else class="message-content" style="color:#909399; font-style:italic">{{ t("chat.thinking") }}</div>

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
                        <div v-for="(source, sIdx) in msg.sources" :key="sIdx" style="font-size: 13px; color: #444746; margin-top: 4px;">
                          {{ formatSourceReference(source) }}
                        </div>
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
                     <el-tooltip :content="t('chat.clearChat')" placement="top">
                            <el-button circle size="small" @click="reset" :icon="Close" style="border:none; background:transparent;" />
                         </el-tooltip>
                         <el-select v-model="selectedMode" size="small" class="mode-select">
                             <el-option
                               v-for="mode in modeOptions"
                               :key="mode.value"
                               :label="mode.label"
                               :value="mode.value"
                             />
                         </el-select>
                         <el-select v-model="selectedModel" size="small" style="width: 120px;">
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
      </template>

    </main>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, onMounted } from "vue";
import { useRouter } from "vue-router";
import { apiUrl } from "../api/client";
import { streamSsePost } from "../api/sse";
import { listAccessibleSpaces } from "../api/docs";
import { Plus, SwitchButton, Position, Close, Collection } from "@element-plus/icons-vue";
import { clearAuthSession, getAccessToken, getUsernameFromAccessToken } from "../utils/auth";
import { useI18n } from "vue-i18n";

const router = useRouter();
const { t } = useI18n();
const username = ref(getUsernameFromAccessToken(getAccessToken()) || localStorage.getItem("auth_user") || "user");
const conversationId = ref(`conv-${Math.random().toString(36).slice(2, 8)}`);
const question = ref("");
const selectedSpaces = ref<string[]>([]);
const selectedModel = ref("ollama");
const selectedMode = ref<"rag" | "agent">("rag");
const modeOptions = [
  { value: "rag", label: t("chat.modeFast") },
  { value: "agent", label: t("chat.modeAgent") },
];

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

onMounted(async () => {
    try {
        spaceOptions.value = await listAccessibleSpaces();
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

const formatSourceReference = (source: any) => {
  const title = source.doc_uuid ? (source.file_name || source.source || t("chat.document")) : t("chat.unknownSource");
  if (source.page_number) {
    return t("chat.sourceReferenceWithPage", { title, page: formatPageValue(source.page_number) });
  }
  return t("chat.sourceReferenceWithoutPage", { title });
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
    const fileName = String(s.file_name || s.source || s.doc_uuid || "Unknown");
    if (!seenFiles.has(fileName)) {
      seenFiles.add(fileName);
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
/* --- Gemini-like Layout --- */

.chat-shell {
  display: flex;
  height: 100vh;
  background-color: #fff;
  font-family: 'Google Sans', 'Inter', -apple-system, BlinkMacSystemFont, sans-serif;
}

/* Sidebar */
.chat-sidebar {
  width: 260px;
  background-color: #f0f4f9;
  display: flex;
  flex-direction: column;
  padding: 12px;
}
.chat-sidebar-header {
  padding: 12px;
  margin-bottom: 20px;
}
.chat-title {
  font-size: 20px;
  font-weight: 500;
  color: #1f1f1f;
}
.chat-sub {
  font-size: 12px;
  color: #444746;
  margin-top: 4px;
}

.chat-action {
  width: 100%;
  justify-content: flex-start;
  margin-bottom: 8px;
  border: none;
  background-color: #dde3ea;
  color: #1f1f1f;
  border-radius: 20px; 
  font-weight: 500;
  padding: 20px 16px; 
}
.chat-action:hover {
  background-color: #cdd6e0;
}

.chat-history {
  flex: 1;
  overflow-y: auto;
  margin-top: 20px;
}
.chat-history-title {
  font-size: 12px;
  font-weight: 500;
  color: #444746;
  margin-bottom: 8px;
  padding-left: 12px;
}
.chat-history-item {
  padding: 10px 16px;
  border-radius: 18px;
  font-size: 14px;
  color: #444746;
  cursor: pointer;
  margin-bottom: 4px;
  transition: background 0.2s;
}
.chat-history-item:hover {
  background-color: #dde3ea;
}

/* Main Chat Area */
.chat-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  position: relative;
  background-color: #fff;
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
  color: #c4c7c5;
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
}

.chat-input-bottom {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  padding: 20px;
  padding-bottom: 30px;
  background: linear-gradient(180deg, rgba(255,255,255,0) 0%, rgba(255,255,255,1) 40%);
  display: flex;
  justify-content: center;
}

/* --- Reusable Components --- */

.chat-input-container {
    width: 100%;
    max-width: 800px;
    background-color: #f0f4f9;
    border-radius: 30px;
    padding: 16px 24px; 
    display: flex;
    flex-direction: column;
    transition: background-color 0.2s;
}
.chat-input-container:focus-within {
    background-color: #e9eef6;
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
    color: #1f1f1f;
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
    color: #444746;
}
.input-controls {
    display: flex;
    gap: 10px;
    align-items: center;
}
.mode-select {
    width: 120px;
}
.send-btn {
    border-radius: 50%;
    width: 40px;
    height: 40px;
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
    width: 110px;
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
  background-color: #f0f4f9;
  color: #1f1f1f;
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
  background-color: transparent;
  color: #1f1f1f;
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
    border-top: 1px solid #f0f0f0; /* Softer border */
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
    background: #e8f0fe;
    color: #1a73e8;
}
.agent-revised-badge {
    background: #eef7e8;
    color: #3b7a1c;
}
.agent-trace-panel {
    margin-top: 10px;
    background: #f8fafc;
    border: 1px solid #e5eaf3;
    border-radius: 14px;
    padding: 10px 14px;
}
.agent-trace-panel summary {
    cursor: pointer;
    font-size: 13px;
    color: #444746;
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
    color: #1a73e8;
    font-weight: 600;
}
.agent-log-text {
    color: #444746;
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
    color: #444746;
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
</style>
