<template>
  <div class="chat-shell">
    <aside class="chat-sidebar">
      <div class="chat-sidebar-header">
        <div class="chat-title">Campus KB</div>
        <div class="chat-sub">Signed in as {{ username }}</div>
      </div>
      
      <!-- Knowledge Base Selector -->
      <div class="kb-selector" style="margin-bottom: 20px; padding: 0 12px;">
          <div style="font-size:12px; color:#444746; margin-bottom:8px; font-weight:500">Knowledge Base (Tag)</div>
          <el-select v-model="selectedTag" placeholder="Select Knowledge Base" size="small" clearable>
            <template #prefix><el-icon><Collection /></el-icon></template>
            <el-option v-for="tag in tagsOptions" :key="tag" :label="tag" :value="tag" />
          </el-select>
      </div>

      <el-button class="chat-action" @click="reset">
         <el-icon style="margin-right:8px"><Plus /></el-icon> New chat
      </el-button>
      
      <div class="chat-history">
        <div class="chat-history-title">Recent</div>
        <div class="chat-history-item" @click="reset">New Conversation</div>
        <!-- Placeholder history items -->
        <div class="chat-history-item">Campus Guidelines</div>
        <div class="chat-history-item">Library Hours</div>
      </div>
      
      <div style="margin-top:auto">
          <el-button text @click="logout" style="width:100%; justify-content:flex-start; color:#444746">
              <el-icon style="margin-right:8px"><SwitchButton /></el-icon> Sign out
          </el-button>
      </div>
    </aside>
    
    <main class="chat-main" :class="{ 'is-centered-mode': messages.length === 0 }">
      
      <!-- Initial State: Centered Greeting & Input -->
      <div v-if="messages.length === 0" class="chat-center-container">
          <div class="chat-greeting">
              <div class="greeting-text">Hello, {{ username }}</div>
              <div class="greeting-sub">How can I help you today?</div>
          </div>
          
          <div class="chat-input-center-wrapper">
             <div class="chat-input-container">
                <textarea 
                    v-model="question" 
                    class="chat-input-textarea" 
                    placeholder="Ask me anything..." 
                    rows="1"
                    @input="adjustTextareaHeight"
                    @keydown.enter.exact.prevent="startChat"
                ></textarea>
                <div class="chat-input-actions">
                     <div class="input-tip">Topikachu RAG can make mistakes. Check important info.</div>
                     <div style="display:flex; gap:10px; align-items:center">
                         <el-select v-model="selectedModel" size="small" style="width: 120px;">
                             <el-option label="Qwen 2.5" value="ollama" />
                             <el-option label="DeepSeek" value="deepseek" />
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
                         
                         <span style="font-weight:600; font-size:14px; margin-top: 2px;">{{ msg.modelName || 'Assistant' }}</span>
                    </div>

                    <div v-if="msg.content" class="message-content" style="white-space: pre-wrap;" v-html="renderMarkdown(msg.content)"></div>
                    <div v-else class="message-content" style="color:#909399; font-style:italic">Thinking...</div>
                    
                    <!-- Sources -->
                    <div v-if="msg.sources && msg.sources.length > 0" class="message-sources">
                        <div v-for="(source, sIdx) in msg.sources" :key="sIdx" style="font-size: 13px; color: #444746; margin-top: 4px;">
                            此回答引用自《{{ source.doc_uuid ? (source.file_name || source.source || 'Document') : 'Unknown Source' }}》
                            <span v-if="source.page_number">第 {{ source.page_number }} 页</span>
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
                    placeholder="Ask me anything..." 
                    rows="1"
                    @input="adjustTextareaHeight"
                    @keydown.enter.exact.prevent="startChat"
                ></textarea>
                <div class="chat-input-actions">
                    <div class="input-tip">Topikachu RAG can make mistakes. Check important info.</div>
                    <div style="display:flex; gap:10px; align-items:center">
                         <el-tooltip content="Clear Chat" placement="top">
                            <el-button circle size="small" @click="reset" :icon="Close" style="border:none; background:transparent;" />
                         </el-tooltip>
                         <el-select v-model="selectedModel" size="small" style="width: 120px;">
                             <el-option label="Qwen 2.5" value="ollama" />
                             <el-option label="DeepSeek" value="deepseek" />
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
import { ref, nextTick, reactive, onMounted } from "vue";
import { useRouter } from "vue-router";
import { apiUrl, getAuthHeader, clearTokens } from "../api/client";
import { streamSsePost } from "../api/sse";
import { getAllTags } from "../api/docs";
import { Plus, SwitchButton, Position, Close, Collection } from "@element-plus/icons-vue";

// Simple markdown render
const renderMarkdown = (text: string) => {
    return text.replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>');
}

const router = useRouter();
const username = ref(localStorage.getItem("auth_user") || "user");
const conversationId = ref(`conv-${Math.random().toString(36).slice(2, 8)}`);
const question = ref("");
const selectedTag = ref("");
const selectedModel = ref("ollama");

interface Message {
    role: 'user' | 'assistant';
    content: string;
    modelName?: string;
    sources?: any[];
}

const messages = ref<Message[]>([]);
const loading = ref(false);
const tagsOptions = ref<string[]>([]);

onMounted(async () => {
    try {
        tagsOptions.value = await getAllTags();
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

const startChat = async () => {
  if(!question.value.trim()) return;
  
  const userInput = question.value;
  question.value = "";
  
  const currentModelName = selectedModel.value === 'deepseek' ? 'DeepSeek' : 'Qwen 2.5';
  
  // Add User Message
  messages.value.push({ role: 'user', content: userInput });
  scrollBottom();
  
  // Add Assistant Placeholder
  const assistantMsg = reactive<Message>({ role: 'assistant', content: "", modelName: currentModelName, sources: [] });
  messages.value.push(assistantMsg);
  
  loading.value = true;
  try {
    await streamSsePost(
      apiUrl(`/api/v1/chat?conversationId=${encodeURIComponent(conversationId.value)}`),
      { userInput, tags: selectedTag.value ? [selectedTag.value] : [], modelId: selectedModel.value },
      (event, data) => {
        if (event === 'sources') {
            try {
                const rawSources = JSON.parse(data);
                // Deduplicate sources by filename
                const uniqueSources = [];
                const seenFiles = new Set();
                
                rawSources.forEach((s: any) => {
                    const fileName = s.source || 'Unknown';
                    if (!seenFiles.has(fileName)) {
                        seenFiles.add(fileName);
                        uniqueSources.push(s);
                    }
                });
                
                assistantMsg.sources = uniqueSources;
            } catch(e) { console.error("Failed to parse sources", e); }
        } else if (event === 'message') {
            assistantMsg.content += data;
            scrollBottom();
        }
      },
      getAuthHeader()
    );
  } catch (err: any) {
    assistantMsg.content += `\n[Error: ${err?.message || err}]`;
  } finally {
    loading.value = false;
  }
};

const reset = () => {
  messages.value = [];
  question.value = "";
  conversationId.value = `conv-${Math.random().toString(36).slice(2, 8)}`;
};

const logout = () => {
  clearTokens();
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
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
}
.input-tip {
    font-size: 12px;
    color: #444746;
}
.send-btn {
    border-radius: 50%;
    width: 40px;
    height: 40px;
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
