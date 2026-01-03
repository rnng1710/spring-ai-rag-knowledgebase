<template>
  <div class="page">
    <div class="header" style="margin-bottom: 16px;">
      <div>
        <h2>Admin Console</h2>
        <div class="muted">Signed in as {{ username }}</div>
      </div>
      <div>
        <el-button @click="goIndex" style="margin-right: 8px;">Index</el-button>
        <el-button @click="logout">Switch Account</el-button>
      </div>
    </div>
    <div class="grid grid-2">
      <div class="card">
        <h2>Upload Document</h2>
        <div class="muted">POST /api/v1/docs/upload (admin only)</div>
        <el-form label-width="120px" style="margin-top: 12px;">
          <el-form-item label="File">
            <input type="file" @change="onFileChange" />
          </el-form-item>
          <el-form-item label="File Name">
            <el-input v-model="fileName" placeholder="optional" />
          </el-form-item>
          <el-form-item label="Overwrite">
            <el-switch v-model="overwrite" />
          </el-form-item>
          <el-button type="primary" :loading="uploading" @click="upload">Upload</el-button>
        </el-form>
        <div style="margin-top: 12px;" class="muted">
          {{ uploadStatus }}
        </div>
      </div>

      <div class="card">
        <div class="header" style="margin-bottom: 12px;">
          <div>
            <h2>Document Status</h2>
            <div class="muted">Placeholder table. Backend API not implemented yet.</div>
          </div>
          <el-button disabled>Refresh</el-button>
        </div>
        <el-table :data="rows" border style="width: 100%">
          <el-table-column prop="docUuid" label="doc_uuid" width="180" />
          <el-table-column prop="filename" label="filename" />
          <el-table-column prop="status" label="status" width="120" />
          <el-table-column prop="updatedAt" label="updated_at" width="180" />
          <el-table-column prop="error" label="error" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { ElMessage } from "element-plus";
import { useRouter } from "vue-router";
import { apiUrl, getAuthHeader } from "../api/client";

const router = useRouter();
const username = ref(localStorage.getItem("auth_user") || "admin");
const fileName = ref("");
const overwrite = ref(false);
const file = ref<File | null>(null);
const uploading = ref(false);
const uploadStatus = ref("No upload yet.");

const onFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement;
  file.value = target.files?.[0] ?? null;
};

const upload = async () => {
  if (!file.value) {
    ElMessage.warning("Please select a file first.");
    return;
  }
  uploading.value = true;
  uploadStatus.value = "Uploading...";

  const form = new FormData();
  form.append("file", file.value);
  if (fileName.value.trim()) {
    form.append("fileName", fileName.value.trim());
  }
  form.append("overwrite", overwrite.value ? "true" : "false");

  try {
    const response = await fetch(apiUrl("/api/v1/docs/upload"), {
      method: "POST",
      headers: {
        Authorization: getAuthHeader()
      },
      body: form
    });
    if (!response.ok) {
      throw new Error(`Upload failed: ${response.status}`);
    }
    uploadStatus.value = "Upload succeeded.";
    ElMessage.success("Upload succeeded.");
  } catch (err: any) {
    uploadStatus.value = `Upload failed: ${err?.message || err}`;
    ElMessage.error(uploadStatus.value);
  } finally {
    uploading.value = false;
  }
};

const rows = [
  {
    docUuid: "demo-uuid",
    filename: "campus-handbook.pdf",
    status: "INDEXED",
    updatedAt: "2025-01-01 12:00:00",
    error: ""
  }
];

const goIndex = () => {
  router.push("/admin/index");
};

const logout = () => {
  localStorage.removeItem("auth_basic");
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
  router.push("/login");
};
</script>
