<template>
  <div class="user-management">
    <div class="header-actions">
      <h2>User Management</h2>
      <el-button type="primary" @click="showAddDialog">
        <el-icon style="margin-right:8px"><Plus /></el-icon> Add User
      </el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="users" v-loading="loading" style="width: 100%">
        <el-table-column prop="id" label="ID" width="220" />
        <el-table-column prop="username" label="Username" />
        <el-table-column prop="role" label="Role">
          <template #default="scope">
            <el-tag :type="scope.row.role === 'ADMIN' ? 'danger' : 'success'" size="small">
              {{ scope.row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" label="Status">
            <template #default="scope">
                <el-tag :type="scope.row.enabled ? 'success' : 'info'" size="small" effect="plain">
                    {{ scope.row.enabled ? 'Active' : 'Disabled' }}
                </el-tag>
            </template>
        </el-table-column>
        <el-table-column label="Actions" width="250" fixed="right">
          <template #default="scope">
            <el-button size="small" @click="handleResetPassword(scope.row)">Reset Pwd</el-button>
            <el-button 
                size="small" 
                type="danger" 
                @click="handleDelete(scope.row)"
                :disabled="scope.row.username === 'admin'"
            >
                Delete
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Add/Edit User Dialog -->
    <el-dialog
      v-model="dialogVisible"
      title="Add New User"
      width="500px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-form-item label="Username" prop="username">
          <el-form-item>
             <el-input v-model="form.username" placeholder="Enter username" />
          </el-form-item>
        </el-form-item>
        <el-form-item label="Password" prop="password">
           <el-input v-model="form.password" type="password" show-password placeholder="Initial password" />
        </el-form-item>
        <el-form-item label="Role" prop="role">
          <el-select v-model="form.role" placeholder="Select role">
            <el-option label="User" value="USER" />
            <el-option label="Admin" value="ADMIN" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">Cancel</el-button>
          <el-button type="primary" :loading="submitting" @click="submitForm">
            Confirm
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue';
import { Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { FormInstance, FormRules } from 'element-plus';
import { getUsers, createUser, deleteUser, resetUserPassword, type User } from '../../api/user';

const users = ref<User[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const submitting = ref(false);
const formRef = ref<FormInstance>();

const form = reactive({
  username: '',
  password: '',
  role: 'USER'
});

const rules = reactive<FormRules>({
  username: [{ required: true, message: 'Please input username', trigger: 'blur' }],
  password: [{ required: true, message: 'Please input password', trigger: 'blur' }],
  role: [{ required: true, message: 'Please select role', trigger: 'change' }]
});

const fetchUsers = async () => {
    loading.value = true;
    try {
        users.value = await getUsers();
    } catch(e) {
        ElMessage.error("Failed to load users");
    } finally {
        loading.value = false;
    }
};

const showAddDialog = () => {
    form.username = '';
    form.password = '';
    form.role = 'USER';
    dialogVisible.value = true;
};

const submitForm = async () => {
    if (!formRef.value) return;
    await formRef.value.validate(async (valid) => {
        if (valid) {
            submitting.value = true;
            try {
                await createUser(form);
                ElMessage.success("User created successfully");
                dialogVisible.value = false;
                fetchUsers();
            } catch(e: any) {
                ElMessage.error(e.message || "Failed to create user");
            } finally {
                submitting.value = false;
            }
        }
    });
};

const handleDelete = (row: User) => {
    ElMessageBox.confirm(
        `Are you sure to delete user "${row.username}"?`,
        'Warning',
        {
            confirmButtonText: 'Delete',
            cancelButtonText: 'Cancel',
            type: 'warning',
        }
    ).then(async () => {
        try {
            await deleteUser(row.id);
            ElMessage.success("Deleted successfully");
            fetchUsers();
        } catch(e: any) {
             ElMessage.error(e.message || "Failed to delete");
        }
    });
};

const handleResetPassword = (row: User) => {
     ElMessageBox.prompt('Please input new password', 'Reset Password', {
        confirmButtonText: 'OK',
        cancelButtonText: 'Cancel',
        inputType: 'password',
      }).then(async ({ value }) => {
          if(!value) return;
          try {
              await resetUserPassword(row.id, value);
              ElMessage.success(`Password for ${row.username} reset successfully`);
          } catch(e: any) {
              ElMessage.error(e.message || "Failed to reset password");
          }
      });
};

onMounted(() => {
    fetchUsers();
});
</script>

<style scoped>
.user-management {
    height: 100%;
    display: flex;
    flex-direction: column;
}
.header-actions {
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 20px;
}
.header-actions h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 500;
}
.table-card {
    flex: 1;
    overflow: hidden;
}
</style>
