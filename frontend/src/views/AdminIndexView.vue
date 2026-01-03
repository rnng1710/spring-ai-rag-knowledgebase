<template>
  <div class="page">
    <div class="header" style="margin-bottom: 16px;">
      <div>
        <h2>Admin Console</h2>
        <div class="muted">Signed in as {{ username }}</div>
      </div>
      <div>
        <el-button @click="goStatus" style="margin-right: 8px;">Status</el-button>
        <el-button @click="logout">Switch Account</el-button>
      </div>
    </div>
    <div class="grid grid-2">
      <div class="card">
        <div class="muted">Trigger /api/v1/index and stream file paths</div>
        <el-form label-width="120px">
          <el-button type="primary" @click="startIndexing" :loading="loading">Run Index</el-button>
        </el-form>
        <div style="margin-top: 12px;">
          <span class="status-pill">{{ status }}</span>
        </div>
      </div>
      <div class="card">
        <h3>Index Stream</h3>
        <div class="stream-box">{{ output }}</div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { apiUrl, getAuthHeader } from "../api/client";
import { streamSsePost } from "../api/sse";

const router = useRouter();
const username = ref(localStorage.getItem("auth_user") || "admin");
const output = ref("");
const status = ref("Idle");
const loading = ref(false);

const startIndexing = async () => {
  output.value = "";
  status.value = "Running";
  loading.value = true;
  try {
    await streamSsePost(
      apiUrl("/api/v1/index"),
      {},
      (data) => {
        output.value += `${data}\n`;
      },
      getAuthHeader()
    );
    status.value = "Completed";
  } catch (err: any) {
    status.value = "Failed";
    output.value = `Error: ${err?.message || err}`;
  } finally {
    loading.value = false;
  }
};

const goStatus = () => {
  router.push("/admin/status");
};

const logout = () => {
  localStorage.removeItem("auth_basic");
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
  router.push("/login");
};
</script>
