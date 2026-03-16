<template>
  <div class="page">
    <div class="header" style="margin-bottom: 16px;">
      <div>
        <h2>{{ t("adminStatus.title") }}</h2>
        <div class="muted">{{ t("chat.signedInAs", { username }) }}</div>
      </div>
      <div>
        <el-button @click="goIndex" style="margin-right: 8px;">{{ t("adminStatus.index") }}</el-button>
        <el-button @click="logout">{{ t("adminStatus.switchAccount") }}</el-button>
      </div>
    </div>
    <div class="grid grid-2">
      <div class="card">
        <h2>{{ t("adminStatus.uploadDocument") }}</h2>
        <div class="muted">{{ t("adminStatus.uploadApiHint") }}</div>
        <el-form label-width="120px" style="margin-top: 12px;">
          <el-form-item :label="t('common.file')">
            <input type="file" @change="onFileChange" />
          </el-form-item>
          <el-form-item :label="t('adminStatus.fileName')">
            <el-input v-model="fileName" :placeholder="t('common.optional')" />
          </el-form-item>
          <el-form-item :label="t('common.overwrite')">
            <el-switch v-model="overwrite" />
          </el-form-item>
          <el-button type="primary" :loading="uploading" @click="upload">{{ t("common.upload") }}</el-button>
        </el-form>
        <div style="margin-top: 12px;" class="muted">
          {{ uploadStatus }}
        </div>
      </div>

      <div class="card">
        <div class="header" style="margin-bottom: 12px;">
          <div>
            <h2>{{ t("adminStatus.documentStatus") }}</h2>
            <div class="muted">{{ t("adminStatus.placeholderHint") }}</div>
          </div>
          <el-button disabled>{{ t("adminStatus.refresh") }}</el-button>
        </div>
        <el-table :data="rows" border style="width: 100%">
          <el-table-column prop="docUuid" :label="t('adminStatus.docUuid')" width="180" />
          <el-table-column prop="filename" :label="t('docs.filename')" />
          <el-table-column prop="status" :label="t('common.status')" width="120" />
          <el-table-column prop="updatedAt" :label="t('adminStatus.updatedAt')" width="180" />
          <el-table-column prop="error" :label="t('docs.errorLabel')" />
        </el-table>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { ElMessage } from "element-plus";
import { useRouter } from "vue-router";
import { apiUrl, authFetch } from "../api/client";
import { clearAuthSession, getAccessToken, getUsernameFromAccessToken } from "../utils/auth";
import { useI18n } from "vue-i18n";

const router = useRouter();
const { t } = useI18n();
const username = ref(getUsernameFromAccessToken(getAccessToken()) || localStorage.getItem("auth_user") || "admin");
const fileName = ref("");
const overwrite = ref(false);
const file = ref<File | null>(null);
const uploading = ref(false);
const uploadStatus = ref(t("adminStatus.noUploadYet"));

const onFileChange = (event: Event) => {
  const target = event.target as HTMLInputElement;
  file.value = target.files?.[0] ?? null;
};

const upload = async () => {
  if (!file.value) {
    ElMessage.warning(t("adminStatus.selectFileFirst"));
    return;
  }
  uploading.value = true;
  uploadStatus.value = t("adminStatus.uploading");

  const form = new FormData();
  form.append("file", file.value);
  if (fileName.value.trim()) {
    form.append("fileName", fileName.value.trim());
  }
  form.append("overwrite", overwrite.value ? "true" : "false");

  try {
    const response = await authFetch(apiUrl("/api/v1/docs/upload"), {
      method: "POST",
      body: form
    });
    let payload: any = null;
    let fallbackMsg = "";
    try {
      payload = await response.clone().json();
    } catch {
      try {
        fallbackMsg = await response.text();
      } catch {
        fallbackMsg = "";
      }
    }

    const msgFromPayload = typeof payload?.msg === "string" ? payload.msg.trim() : "";
    const msg = msgFromPayload || (typeof fallbackMsg === "string" ? fallbackMsg.trim() : "");

    if (!response.ok) {
      throw new Error(t("adminStatus.uploadFailedHttp", { status: response.status, msg: msg ? ` ${msg}` : "" }));
    }

    if (payload?.success !== true || payload?.code !== 0) {
      const defaultMsg = t("adminStatus.uploadFailedUnknown");
      throw new Error(msgFromPayload || defaultMsg);
    }

    const data = payload?.data ?? {};
    const created = data?.created === true;
    const docUuid = data?.docUuid ?? data?.doc_uuid ?? "";
    const name = data?.fileName ?? data?.filename ?? fileName.value.trim() ?? file.value.name;
    const status = data?.status ?? "";
    const hash = data?.hash ?? "";

    if (created) {
      ElMessage.success(t("adminStatus.uploadSuccessful"));
      uploadStatus.value = t("adminStatus.uploadCreatedStatus", { docUuid, name, status, hash });
    } else {
      ElMessage.info(t("adminStatus.uploadDuplicated"));
      uploadStatus.value = t("adminStatus.uploadExistedStatus", { docUuid, name, status, hash });
    }
  } catch (err: any) {
    uploadStatus.value = t("adminStatus.uploadFailed");
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
  clearAuthSession();
  router.push("/login");
};
</script>
