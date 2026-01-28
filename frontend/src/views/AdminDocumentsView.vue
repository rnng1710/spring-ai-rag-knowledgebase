<template>
  <div class="docs-view">
    <!-- Search Area -->
    <el-card shadow="never" class="search-card">
      <el-form :inline="true" @submit.prevent="handleSearch">
        <el-form-item label="Filename">
          <el-input v-model="query.keyword" placeholder="Search filename" clearable @clear="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch" :icon="Search">Search</el-button>
          <el-button @click="resetSearch" :icon="Refresh">Reset</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Toolbar -->
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openUploadDialog">Upload</el-button>
      <el-button type="danger" :icon="Delete" :disabled="selectedIds.length === 0" @click="handleBatchDelete">
        Batch Delete
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
      <el-table-column prop="fileName" label="Filename" min-width="200" />
      <el-table-column prop="status" label="Status" width="120">
        <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createDate" label="Created At" width="180" />
      <el-table-column label="Actions" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="danger" size="small" @click="handleDelete(row)">Delete</el-button>
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
    <el-dialog v-model="dialogVisible" title="Upload Documents" width="600px" destroy-on-close>
      <el-tabs v-model="activeTab">
        <el-tab-pane label="Single File" name="single">
           <el-form label-width="100px" @submit.prevent>
              <el-form-item label="File">
                 <input type="file" ref="singleFileInput" @change="onSingleFileChange" />
              </el-form-item>
              <el-form-item label="Rename">
                  <el-input v-model="uploadForm.fileName" placeholder="Optional" />
              </el-form-item>
              <el-form-item label="Overwrite">
                  <el-switch v-model="uploadForm.overwrite" />
              </el-form-item>
              <el-form-item label="Tags">
                   <el-select
                      v-model="uploadForm.tagsInput"
                      multiple
                      filterable
                      allow-create
                      default-first-option
                      placeholder="Select or Create Tags"
                   >
                     <el-option v-for="tag in tagsOptions" :key="tag" :label="tag" :value="tag" />
                   </el-select>
              </el-form-item>
           </el-form>
        </el-tab-pane>
        <el-tab-pane label="Batch Files" name="batch">
            <el-form label-width="100px" @submit.prevent>
              <el-form-item label="Files">
                 <input type="file" multiple ref="batchFileInput" @change="onBatchFileChange" />
                 <div class="tip">Selected: {{ batchFiles.length }} files</div>
              </el-form-item>
              <el-form-item label="Overwrite">
                  <el-switch v-model="uploadForm.overwrite" />
              </el-form-item>
              <el-form-item label="Tags">
                  <el-input v-model="uploadForm.tagsInput" placeholder="Comma separated, e.g. finance" />
              </el-form-item>
           </el-form>
        </el-tab-pane>
        <el-tab-pane label="Folder" name="folder">
             <el-form label-width="100px" @submit.prevent>
              <el-form-item label="Folder">
                 <input type="file" webkitdirectory directory ref="folderInput" @change="onFolderChange" />
                 <div class="tip">Selected: {{ folderFiles.length }} files</div>
              </el-form-item>
              <el-form-item label="Overwrite">
                  <el-switch v-model="uploadForm.overwrite" />
              </el-form-item>
              <el-form-item label="Tags">
                  <el-input v-model="uploadForm.tagsInput" placeholder="Comma separated, e.g. finance" />
              </el-form-item>
           </el-form>
        </el-tab-pane>
      </el-tabs>
      <template #footer>
        <el-button @click="dialogVisible = false">Cancel</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">Upload</el-button>
      </template>
      
      <!-- Upload Log Area -->
      <div v-if="uploadLogs.length > 0" class="upload-logs">
          <div v-for="(log, idx) in uploadLogs" :key="idx" :class="log.type">{{ log.msg }}</div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, reactive } from "vue";
import { Search, Refresh, Plus, Delete } from "@element-plus/icons-vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { listDocs, deleteDoc, deleteDocsBatch, uploadSingle, uploadBatch, getAllTags, type Doc } from "../api/docs";
import { connectSse, disconnectSse, type EtlMessage } from "../api/sse-fetch";

// --- State ---
const loading = ref(false);
const tableData = ref<Doc[]>([]);
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

// --- Lifecycle ---
onMounted(async () => {
  await Promise.all([loadData(), loadTags()]);
  // Connect SSE
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
      }
  });
});

onUnmounted(() => {
    disconnectSse();
});

const loadTags = async () => {
    try {
        tagsOptions.value = await getAllTags();
    } catch (e) {
        console.error("Failed to load tags", e);
    }
};

// --- Methods ---
const loadData = async () => {
  loading.value = true;
  try {
    const res = await listDocs(pagination.current, pagination.size, query.keyword);
    tableData.value = res.records;
    pagination.total = res.total;
  } catch (e: any) {
    ElMessage.error(e.message);
  } finally {
    loading.value = false;
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

const handleDelete = async (row: Doc) => {
    try {
        await ElMessageBox.confirm(`Are you sure to delete "${row.fileName}"?`, "Warning", {
            type: "warning"
        });
        await deleteDoc(row.id);
        ElMessage.success("Deleted successfully");
        loadData();
    } catch (e) {
        if(e !== 'cancel') ElMessage.error("Delete failed");
    }
};

const handleBatchDelete = async () => {
    try {
        await ElMessageBox.confirm(`Are you sure to delete ${selectedIds.value.length} items?`, "Warning", {
            type: "warning"
        });
        await deleteDocsBatch(selectedIds.value);
        ElMessage.success("Batch deleted successfully");
        loadData();
    } catch (e) {
         if(e !== 'cancel') ElMessage.error("Batch delete failed");
    }
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
                ElMessage.warning("Please select a file");
                return;
            }
            const res = await uploadSingle(singleFile.value, uploadForm.fileName, uploadForm.overwrite, tags);
            if (res.code === 0) {
                 const data = res.data;
                 if (data.created) {
                     ElMessage.success(`Upload Success: ${data.fileName}`);
                 } else {
                     ElMessage.info(`File already exists: ${data.fileName} (Status: ${data.status})`);
                 }
                 loadData();
                 dialogVisible.value = false; // Auto close
            } else {
                 addLog(`Failed: ${res.msg}`, 'error');
            }
        } else {
            // Batch or Folder
            const files = activeTab.value === 'batch' ? batchFiles.value : folderFiles.value;
            if (files.length === 0) {
                ElMessage.warning("Please select files");
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
                     ElMessage.success(`Batch Upload Completed. New: ${data.successCount - hasExisting}, Existing: ${hasExisting}`);
                 } else if (hasExisting > 0) {
                     ElMessage.info(`Batch Completed. All ${hasExisting} files already existed.`);
                 } else {
                     ElMessage.warning("Batch Completed with no successful uploads.");
                 }

                 loadData();
                 dialogVisible.value = false; // Auto close
            } else {
                 addLog(`Batch Failed: ${res.msg}`, 'error');
            }
        }
    } catch (e: any) {
        addLog(`Error: ${e.message}`, 'error');
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
