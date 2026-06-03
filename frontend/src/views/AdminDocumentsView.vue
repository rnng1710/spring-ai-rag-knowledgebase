<template>
  <div class="docs-view">
    <!-- Search Area -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent="handleSearch">
        <el-form-item :label="t('docs.filename')">
          <el-input v-model="query.keyword" :placeholder="t('docs.searchFilename')" clearable @clear="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch" :icon="Search">{{ t("common.search") }}</el-button>
          <el-button @click="resetSearch" :icon="Refresh">{{ t("common.reset") }}</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Toolbar -->
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openUploadDialog">{{ t("common.upload") }}</el-button>
      <el-button @click="handleBackfillAcl">{{ t("docs.backfillAcl") }}</el-button>
      <el-button type="danger" :icon="Delete" :disabled="selectedIds.length === 0" @click="handleBatchDelete">
        {{ t("docs.batchDelete") }}
      </el-button>
    </div>

    <!-- Table -->
    <el-table
      v-loading="loading"
      :data="tableData"
      border
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="55" />
      <el-table-column label="#" width="80">
        <template #default="scope">
          {{ (pagination.current - 1) * pagination.size + scope.$index + 1 }}
        </template>
      </el-table-column>
      <el-table-column prop="fileName" :label="t('docs.filename')" min-width="200" />
      <el-table-column prop="status" :label="t('common.status')" width="120">
        <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ getStatusLabel(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="spaceCode" :label="t('docs.space')" width="120">
        <template #default="{ row }">
          {{ row.spaceCode || t("docs.publicSpace") }}
        </template>
      </el-table-column>
      <el-table-column prop="ownerDeptId" :label="t('docs.ownerDept')" width="140">
        <template #default="{ row }">
          {{ row.ownerDeptId || "-" }}
        </template>
      </el-table-column>
      <el-table-column prop="isPublic" :label="t('docs.visibility')" width="110">
        <template #default="{ row }">
          <el-tag :type="row.isPublic ? 'success' : 'warning'">
            {{ row.isPublic ? t("docs.visibilityPublic") : t("docs.visibilityRestricted") }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" :label="t('common.createdAt')" width="180" />
      <el-table-column :label="t('common.actions')" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="handleDownload(row)">{{ t("common.download") }}</el-button>
          <el-button link type="primary" size="small" @click="openPermissionDialog(row)">{{ t("common.edit") }}</el-button>
          <el-button link type="danger" size="small" @click="handleDelete(row)">{{ t("common.delete") }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- Pagination -->
    <div class="pagination-container">
      <el-pagination
        v-model:current-page="pagination.current"
        v-model:page-size="pagination.size"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        :total="pagination.total"
        @size-change="loadData"
        @current-change="loadData"
      />
    </div>

    <!-- Upload Dialog -->
    <el-dialog v-model="dialogVisible" :title="t('docs.uploadDocuments')" width="600px" destroy-on-close>
      <el-tabs v-model="activeTab">
      <el-tab-pane :label="t('docs.singleFile')" name="single">
           <el-form label-width="100px" @submit.prevent>
          <el-form-item :label="t('common.file')">
                 <input type="file" ref="singleFileInput" @change="onSingleFileChange" />
              </el-form-item>
          <el-form-item :label="t('docs.rename')">
            <el-input v-model="uploadForm.fileName" :placeholder="t('common.optional')" />
              </el-form-item>
          <el-form-item :label="t('common.overwrite')">
                  <el-switch v-model="uploadForm.overwrite" />
              </el-form-item>
          <el-form-item :label="t('common.tags')">
                   <el-select
                      v-model="uploadForm.tagsInput"
                      multiple
                      filterable
                      allow-create
                      default-first-option
              :placeholder="t('docs.selectOrCreateTags')"
                   >
                     <el-option v-for="tag in tagsOptions" :key="tag" :label="tag" :value="tag" />
                   </el-select>
              </el-form-item>
           </el-form>
        </el-tab-pane>
      <el-tab-pane :label="t('docs.batchFiles')" name="batch">
            <el-form label-width="100px" @submit.prevent>
          <el-form-item :label="t('common.files')">
                 <input type="file" multiple ref="batchFileInput" @change="onBatchFileChange" />
           <div class="tip">{{ t("docs.selectedFiles", { count: batchFiles.length }) }}</div>
              </el-form-item>
          <el-form-item :label="t('common.overwrite')">
                  <el-switch v-model="uploadForm.overwrite" />
              </el-form-item>
          <el-form-item :label="t('common.tags')">
            <el-input v-model="uploadForm.tagsInput" :placeholder="t('docs.commaTagExample')" />
              </el-form-item>
           </el-form>
        </el-tab-pane>
      <el-tab-pane :label="t('common.folder')" name="folder">
             <el-form label-width="100px" @submit.prevent>
          <el-form-item :label="t('common.folder')">
                 <input type="file" webkitdirectory directory ref="folderInput" @change="onFolderChange" />
           <div class="tip">{{ t("docs.selectedFiles", { count: folderFiles.length }) }}</div>
              </el-form-item>
          <el-form-item :label="t('common.overwrite')">
                  <el-switch v-model="uploadForm.overwrite" />
              </el-form-item>
          <el-form-item :label="t('common.tags')">
            <el-input v-model="uploadForm.tagsInput" :placeholder="t('docs.commaTagExample')" />
              </el-form-item>
           </el-form>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
      <el-button @click="dialogVisible = false">{{ t("common.cancel") }}</el-button>
      <el-button type="primary" :loading="uploading" :disabled="!canSubmitUpload" @click="submitUpload">{{ t("common.upload") }}</el-button>
      </template>
      
      <!-- Upload Log Area -->
      <div v-if="uploadLogs.length > 0" class="upload-logs">
          <div v-for="(log, idx) in uploadLogs" :key="idx" :class="log.type">{{ log.msg }}</div>
      </div>
    </el-dialog>

    <el-dialog v-model="permissionDialogVisible" :title="t('docs.editPermissions')" width="520px" destroy-on-close>
      <el-form :model="permissionForm" label-width="120px">
        <el-form-item :label="t('docs.space')">
          <el-input v-model="permissionForm.spaceCode" :placeholder="t('docs.publicSpace')" />
        </el-form-item>
        <el-form-item :label="t('docs.ownerDept')">
          <el-input v-model="permissionForm.ownerDeptId" :placeholder="t('users.enterDeptId')" />
        </el-form-item>
        <el-form-item :label="t('docs.allowedRoles')">
          <el-select v-model="permissionForm.allowedRoles" multiple allow-create filterable default-first-option>
            <el-option label="USER" value="USER" />
            <el-option label="ADMIN" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('docs.allowedDeptIds')">
          <el-input v-model="permissionDeptIdsInput" :placeholder="t('docs.commaDeptExample')" />
        </el-form-item>
        <el-form-item :label="t('docs.publicAccess')">
          <el-switch v-model="permissionForm.isPublic" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="permissionDialogVisible = false">{{ t("common.cancel") }}</el-button>
        <el-button type="primary" :loading="permissionSubmitting" @click="submitPermissions">{{ t("common.confirm") }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, onMounted, onUnmounted, reactive } from "vue";
import { Search, Refresh, Plus, Delete } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import {
  listDocs,
  deleteDoc,
  deleteDocsBatch,
  downloadDoc,
  uploadSingle,
  uploadBatch,
  getAllTags,
  updateDocPermissions,
  backfillAclMetadata,
  type Doc
} from "../api/docs";
import { connectSse, disconnectSse, type EtlMessage } from "../api/sse-fetch";
import { useI18n } from "vue-i18n";

const { t, te } = useI18n();

// --- State ---
const loading = ref(false);
const tableData = ref<Doc[]>([]);
let statusPollingTimer: ReturnType<typeof window.setInterval> | null = null;
const selectedIds = ref<string[]>([]);
const pagination = reactive({
  current: 1,
  size: 10,
  total: 0
});
const query = reactive({
  keyword: ""
});

// --- Upload State ---
const dialogVisible = ref(false);
const activeTab = ref("single");
const uploading = ref(false);
const uploadForm = reactive({
    fileName: "",
    overwrite: false,
    tagsInput: [] as string[]
});
const tagsOptions = ref<string[]>([]);
const singleFile = ref<File | null>(null);
const batchFiles = ref<File[]>([]);
const folderFiles = ref<File[]>([]);
const uploadLogs = ref<{type: string, msg: string}[]>([]);
const permissionDialogVisible = ref(false);
const permissionSubmitting = ref(false);
const editingDocId = ref("");
const permissionDeptIdsInput = ref("");
const permissionForm = reactive({
  spaceCode: "public",
  ownerDeptId: "",
  allowedRoles: [] as string[],
  allowedDeptIds: [] as string[],
  isPublic: true
});

const canSubmitUpload = computed(() => {
  if (uploading.value) {
    return false;
  }
  if (activeTab.value === "single") {
    return singleFile.value !== null;
  }
  if (activeTab.value === "batch") {
    return batchFiles.value.length > 0;
  }
  if (activeTab.value === "folder") {
    return folderFiles.value.length > 0;
  }
  return false;
});

// --- Lifecycle ---
onMounted(async () => {
  await Promise.all([loadData(), loadTags()]);
  startStatusPolling();
  // Connect SSE
  // SSE 静默更新：收到 ETL 状态变更时直接更新表格行状态，无需整页刷新
  connectSse((msg: EtlMessage) => {
      // Find row
      const target = tableData.value.find(d => d.docUuid === msg.docUuid);
      if (target) {
          target.status = msg.status;
          // Optional: We can also show a toast or notification
          if (msg.status === 'COMPLETED') {
              // Maybe reload to get updated metadata if needed?
              // For now, just status update is enough.
          }
      } else {
          loadData(false);
      }
  });
});

onUnmounted(() => {
    disconnectSse();
    stopStatusPolling();
});

const loadTags = async () => {
    try {
        tagsOptions.value = await getAllTags();
    } catch (e) {
        console.error("Failed to load tags", e);
    }
};

// --- Methods ---
const loadData = async (showLoading = true) => {
  if (showLoading) {
    loading.value = true;
  }
  try {
    const res = await listDocs(pagination.current, pagination.size, query.keyword);
    tableData.value = res.records;
    pagination.total = Number(res.total ?? tableData.value.length ?? 0);
  } catch (e: any) {
    ElMessage.error(e.message || t("docs.loadFailed"));
  } finally {
    if (showLoading) {
      loading.value = false;
    }
  }
};

// ETL 中间态集合：这些状态表示文档正在处理中，需轮询刷新直到变为 COMPLETED 或 FAILED
const processingStatuses = new Set(["UPLOADED", "READING", "SPLITTING", "VECTORIZING"]);

const hasProcessingDocs = () => tableData.value.some(doc => processingStatuses.has(doc.status));

const startStatusPolling = () => {
  stopStatusPolling();
  // 3 秒轮询间隔：ETL 处理通常 5-30 秒，3 秒足够及时更新且不造成过大后端压力
  statusPollingTimer = window.setInterval(() => {
    if (!loading.value && hasProcessingDocs()) {
      loadData(false);
    }
  }, 3000);
};

const stopStatusPolling = () => {
  if (statusPollingTimer !== null) {
    window.clearInterval(statusPollingTimer);
    statusPollingTimer = null;
  }
};

const handleSearch = () => {
  pagination.current = 1;
  loadData();
};

const resetSearch = () => {
  query.keyword = "";
  handleSearch();
};

const handleSelectionChange = (selection: Doc[]) => {
  selectedIds.value = selection.map(item => item.id);
};

const handleDownload = async (row: Doc) => {
  try {
    await downloadDoc(row);
  } catch (e: any) {
    ElMessage.error(e.message || t("docs.downloadFailed"));
  }
};

const handleDelete = async (row: Doc) => {
    try {
    await ElMessageBox.confirm(t("docs.deleteConfirm", { name: row.fileName }), t("common.warning"), {
            type: "warning"
        });
        await deleteDoc(row.id);
    ElMessage.success(t("docs.deletedSuccessfully"));
        loadData();
    } catch (e) {
    if(e !== 'cancel') ElMessage.error(t("docs.deleteFailed"));
    }
};

const handleBatchDelete = async () => {
    try {
    await ElMessageBox.confirm(t("docs.deleteCountConfirm", { count: selectedIds.value.length }), t("common.warning"), {
            type: "warning"
        });
        await deleteDocsBatch(selectedIds.value);
    ElMessage.success(t("docs.batchDeletedSuccessfully"));
        loadData();
    } catch (e) {
     if(e !== 'cancel') ElMessage.error(t("docs.batchDeleteFailed"));
    }
};

const openPermissionDialog = (row: Doc) => {
  editingDocId.value = row.id;
  permissionForm.spaceCode = row.spaceCode || "public";
  permissionForm.ownerDeptId = row.ownerDeptId || "";
  permissionForm.allowedRoles = [...(row.allowedRoles || [])];
  permissionForm.allowedDeptIds = [...(row.allowedDeptIds || [])];
  permissionForm.isPublic = !!row.isPublic;
  permissionDeptIdsInput.value = (row.allowedDeptIds || []).join(",");
  permissionDialogVisible.value = true;
};

const parseCommaSeparated = (value: string) => {
  return value
    .split(",")
    .map(item => item.trim())
    .filter(Boolean);
};

const submitPermissions = async () => {
  if (!editingDocId.value) return;
  permissionSubmitting.value = true;
  try {
    permissionForm.allowedDeptIds = parseCommaSeparated(permissionDeptIdsInput.value);
    await updateDocPermissions(editingDocId.value, {
      spaceCode: permissionForm.spaceCode || "public",
      ownerDeptId: permissionForm.ownerDeptId || null,
      allowedRoles: permissionForm.allowedRoles,
      allowedDeptIds: permissionForm.allowedDeptIds,
      isPublic: permissionForm.isPublic
    });
    ElMessage.success(t("docs.updatePermissionsSuccess"));
    permissionDialogVisible.value = false;
    loadData();
  } catch (e: any) {
    ElMessage.error(e.message || t("docs.updatePermissionsFailed"));
  } finally {
    permissionSubmitting.value = false;
  }
};

const handleBackfillAcl = async () => {
  try {
    const count = await backfillAclMetadata();
    ElMessage.success(t("docs.aclBackfillSuccess", { count }));
  } catch (e: any) {
    ElMessage.error(e.message || t("docs.aclBackfillFailed"));
  }
};

const getStatusLabel = (status: string) => {
  const key = `docs.status.${status}`;
  return te(key) ? t(key) : status;
};

const getStatusType = (status: string) => {
    if(status === 'COMPLETED' || status === 'INDEXED') return 'success';
    if(status === 'UPLOADED') return 'info';
    if(status === 'FAILED') return 'danger';
    if(['READING', 'SPLITTING', 'VECTORIZING'].includes(status)) return 'warning';
    return '';
};

// --- Upload Logic ---
const openUploadDialog = () => {
    dialogVisible.value = true;
    activeTab.value = "single";
    uploadLogs.value = [];
    singleFile.value = null;
    batchFiles.value = [];
    folderFiles.value = [];
    uploadForm.fileName = "";
    uploadForm.overwrite = false;
    uploadForm.tagsInput = [];
    loadTags(); // Refresh tags on open
};

const onSingleFileChange = (e: Event) => {
    const target = e.target as HTMLInputElement;
    singleFile.value = target.files?.[0] || null;
};
const onBatchFileChange = (e: Event) => {
    const target = e.target as HTMLInputElement;
    batchFiles.value = Array.from(target.files || []);
};
const onFolderChange = (e: Event) => {
    const target = e.target as HTMLInputElement;
    folderFiles.value = Array.from(target.files || []);
};

const addLog = (msg: string, type='info') => {
    uploadLogs.value.unshift({ msg, type });
};

const submitUpload = async () => {
    uploading.value = true;
    uploadLogs.value = [];
    const tags = uploadForm.tagsInput; // Now it is already an array

    try {
        if (activeTab.value === 'single') {
            if (!singleFile.value) {
              ElMessage.warning(t("docs.pleaseSelectFile"));
                return;
            }
            const res = await uploadSingle(singleFile.value, uploadForm.fileName, uploadForm.overwrite, tags);
            if (res.code === 0) {
                 const data = res.data;
                 if (data.created) {
                   ElMessage.success(t("docs.uploadSuccess", { name: data.fileName }));
                 } else {
                   ElMessage.info(t("docs.fileExists", { name: data.fileName, status: data.status }));
                 }
                 loadData();
                 dialogVisible.value = false; // Auto close
            } else {
                 addLog(t("docs.failed", { msg: res.msg }), 'error');
            }
        } else {
            // Batch or Folder
            const files = activeTab.value === 'batch' ? batchFiles.value : folderFiles.value;
            if (files.length === 0) {
              ElMessage.warning(t("docs.pleaseSelectFiles"));
                return;
            }
            
            const res = await uploadBatch(files, uploadForm.overwrite, tags);
             if (res.code === 0) {
                 const data = res.data; // BatchUploadResponse
                 // Batch might have mix of success/fail/existing
                 // If total > 0, we consider it "done" and close dialog if user wants, 
                 // BUT for batch it's often better to show the log. 
                 // However, user requested auto-close on success. 
                 // Let's close if all were processed (even if some failed or existed).
                 
                 let hasNew = false;
                 let hasExisting = 0;
                 if(data.results) {
                     data.results.forEach((r: any) => {
                         if(r.success) {
                             if (r.new) hasNew = true;
                             else hasExisting++;
                         }
                     });
                 }
                 
                 if (hasNew) {
                   ElMessage.success(t("docs.batchCompletedNew", { newCount: data.successCount - hasExisting, existingCount: hasExisting }));
                 } else if (hasExisting > 0) {
                   ElMessage.info(t("docs.batchCompletedExisting", { count: hasExisting }));
                 } else {
                   ElMessage.warning(t("docs.batchCompletedNone"));
                 }

                 loadData();
                 dialogVisible.value = false; // Auto close
            } else {
                 addLog(t("docs.batchFailed", { msg: res.msg }), 'error');
            }
        }
    } catch (e: any) {
            addLog(t("docs.error", { msg: e.message }), 'error');
    } finally {
        uploading.value = false;
    }
};

</script>

<style scoped>
.docs-view {
    /* padding is handled by Layout */
}
.search-card {
    margin-bottom: 16px;
}
.toolbar {
    margin-bottom: 16px;
}
.pagination-container {
    margin-top: 16px;
    display: flex;
    justify-content: flex-end;
}
.tip {
    font-size: 12px;
    color: #999;
    margin-top: 4px;
}
.upload-logs {
    margin-top: 16px;
    max-height: 150px;
    overflow-y: auto;
    background: #f5f7fa;
    padding: 8px;
    border-radius: 4px;
}
.upload-logs .success { color: green; }
.upload-logs .error { color: red; }
</style>
