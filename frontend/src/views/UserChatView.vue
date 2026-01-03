<template>
  <div class="chat-shell">
    <aside class="chat-sidebar">
      <div class="chat-sidebar-header">
        <div class="login-mark"></div>
        <div>
          <div class="chat-title">Campus KB</div>
          <div class="chat-sub">Signed in as {{ username }}</div>
        </div>
      </div>
      <div class="chat-sidebar-actions">
        <el-button plain class="chat-action" @click="reset">New chat</el-button>
        <el-button plain class="chat-action" @click="logout">Switch account</el-button>
      </div>
      <div class="chat-history">
        <div class="chat-history-title">Recent</div>
        <div class="chat-history-item is-empty">No history yet</div>
        <div class="chat-history-item is-empty">Placeholder thread</div>
        <div class="chat-history-item is-empty">Placeholder thread</div>
      </div>
    </aside>
    <main class="chat-main">
      <div class="chat-header">
        <div>
          <h2>User Chat</h2>
          <div class="muted">Ask questions against the knowledge base.</div>
        </div>
        <div class="chat-meta">Conversation {{ conversationId }}</div>
      </div>
      <div class="chat-thread">
        <div v-if="!output" class="chat-empty">
          <div class="chat-empty-title">Start a conversation</div>
          <div class="chat-empty-sub">Your answers will stream here.</div>
        </div>
        <div v-else class="chat-output">{{ output }}</div>
      </div>
      <div class="chat-input">
        <el-input
          v-model="question"
          type="textarea"
          :rows="3"
          placeholder="Send a message"
        />
        <div class="chat-input-actions">
          <el-button @click="reset">Clear</el-button>
          <el-button type="primary" @click="startChat" :loading="loading">Send</el-button>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { apiUrl, getAuthHeader } from "../api/client";
import { streamSsePost } from "../api/sse";

const router = useRouter();
const username = ref(localStorage.getItem("auth_user") || "user");
const conversationId = ref(`conv-${Math.random().toString(36).slice(2, 8)}`);
const question = ref("");
const output = ref("");
const loading = ref(false);

const startChat = async () => {
  output.value = "";
  loading.value = true;
  try {
    await streamSsePost(
      apiUrl(`/api/v1/chat?conversationId=${encodeURIComponent(conversationId.value)}`),
      { userInput: question.value },
      (data) => {
        output.value += data;
      },
      getAuthHeader()
    );
  } catch (err: any) {
    output.value = `Error: ${err?.message || err}`;
  } finally {
    loading.value = false;
  }
};

const reset = () => {
  output.value = "";
  question.value = "";
};

const logout = () => {
  localStorage.removeItem("auth_basic");
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
  router.push("/login");
};
</script>
