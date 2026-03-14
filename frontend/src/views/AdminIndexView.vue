<template>
  <div class="page">
    <div class="header" style="margin-bottom: 16px;">
      <h2>{{ t("adminIndex.title") }}</h2>
    </div>
    <div class="grid grid-2">
      <div class="card">
        <div class="muted"></div>
        <el-form label-width="120px">
          <el-button type="primary" @click="startIndexing" :loading="loading">{{ t("adminIndex.runIndex") }}</el-button>
        </el-form>
        <div style="margin-top: 12px;">
          <span class="status-pill">{{ status }}</span>
        </div>
      </div>
      <div class="card">
        <h3>{{ t("adminIndex.indexStream") }}</h3>
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
import { useI18n } from "vue-i18n";

const router = useRouter();
const { t } = useI18n();
const username = ref(localStorage.getItem("auth_user") || "admin");
const output = ref("");
const status = ref(t("adminIndex.idle"));
const loading = ref(false);

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
      getAuthHeader()
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
