<template>
  <div class="user-management">
    <div class="header-actions">
      <h2>{{ t("users.title") }}</h2>
      <el-button type="primary" @click="showAddDialog">
        <el-icon style="margin-right:8px"><Plus /></el-icon> {{ t("users.addUser") }}
      </el-button>
    </div>

    <el-card shadow="never" class="table-card">
      <el-table :data="users" v-loading="loading" style="width: 100%">
        <el-table-column prop="id" :label="t('users.id')" width="220" />
        <el-table-column prop="username" :label="t('common.username')" />
        <el-table-column prop="deptId" :label="t('users.deptId')" min-width="140" />
        <el-table-column prop="deptName" :label="t('users.deptName')" min-width="160" />
        <el-table-column prop="role" :label="t('common.role')">
          <template #default="scope">
            <el-tag :type="scope.row.role === 'ADMIN' ? 'danger' : 'success'" size="small">
              {{ scope.row.role }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="enabled" :label="t('common.status')">
            <template #default="scope">
                <el-tag :type="scope.row.enabled ? 'success' : 'info'" size="small" effect="plain">
                    {{ scope.row.enabled ? t("users.active") : t("users.disabled") }}
                </el-tag>
            </template>
        </el-table-column>
        <el-table-column :label="t('common.actions')" width="250" fixed="right">
          <template #default="scope">
            <el-button size="small" @click="showDeptDialog(scope.row)">{{ t("users.editDept") }}</el-button>
            <el-button size="small" @click="handleResetPassword(scope.row)">{{ t("users.resetPwd") }}</el-button>
            <el-button 
                size="small" 
                type="danger" 
                @click="handleDelete(scope.row)"
                :disabled="scope.row.username === 'admin'"
            >
                {{ t("common.delete") }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Add/Edit User Dialog -->
    <el-dialog
      v-model="dialogVisible"
      :title="t('users.addNewUser')"
      width="500px"
      destroy-on-close
    >
      <el-form :model="form" label-width="100px" :rules="rules" ref="formRef">
        <el-form-item :label="t('common.username')" prop="username">
          <el-form-item>
             <el-input v-model="form.username" :placeholder="t('users.enterUsername')" />
          </el-form-item>
        </el-form-item>
        <el-form-item :label="t('common.password')" prop="password">
           <el-input v-model="form.password" type="password" show-password :placeholder="t('users.initialPassword')" />
        </el-form-item>
        <el-form-item :label="t('common.role')" prop="role">
          <el-select v-model="form.role" :placeholder="t('users.selectRole')">
            <el-option :label="t('users.userRole')" value="USER" />
            <el-option :label="t('users.adminRole')" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('users.deptId')">
          <el-input v-model="form.deptId" :placeholder="t('users.enterDeptId')" />
        </el-form-item>
        <el-form-item :label="t('users.deptName')">
          <el-input v-model="form.deptName" :placeholder="t('users.enterDeptName')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="dialogVisible = false">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" :loading="submitting" @click="submitForm">
            {{ t("common.confirm") }}
          </el-button>
        </span>
      </template>
    </el-dialog>

    <el-dialog
      v-model="deptDialogVisible"
      :title="t('users.editDept')"
      width="420px"
      destroy-on-close
    >
      <el-form :model="deptForm" label-width="100px">
        <el-form-item :label="t('users.deptId')">
          <el-input v-model="deptForm.deptId" :placeholder="t('users.enterDeptId')" />
        </el-form-item>
        <el-form-item :label="t('users.deptName')">
          <el-input v-model="deptForm.deptName" :placeholder="t('users.enterDeptName')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button @click="deptDialogVisible = false">{{ t("common.cancel") }}</el-button>
          <el-button type="primary" :loading="deptSubmitting" @click="submitDeptForm">
            {{ t("common.confirm") }}
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
import { getUsers, createUser, deleteUser, resetUserPassword, updateUserDepartment, type User } from '../../api/user';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

const users = ref<User[]>([]);
const loading = ref(false);
const dialogVisible = ref(false);
const submitting = ref(false);
const deptDialogVisible = ref(false);
const deptSubmitting = ref(false);
const formRef = ref<FormInstance>();
const editingUserId = ref<string>("");

const form = reactive({
  username: '',
  password: '',
  role: 'USER',
  deptId: '',
  deptName: ''
});

const deptForm = reactive({
  deptId: '',
  deptName: ''
});

const rules = reactive<FormRules>({
  username: [{ required: true, message: t('users.inputUsernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('users.inputPasswordRequired'), trigger: 'blur' }],
  role: [{ required: true, message: t('users.selectRoleRequired'), trigger: 'change' }]
});

const fetchUsers = async () => {
    loading.value = true;
    try {
        users.value = await getUsers();
    } catch(e) {
      ElMessage.error(t("users.loadUsersFailed"));
    } finally {
        loading.value = false;
    }
};

const showAddDialog = () => {
    form.username = '';
    form.password = '';
    form.role = 'USER';
    form.deptId = '';
    form.deptName = '';
    dialogVisible.value = true;
};

const submitForm = async () => {
    if (!formRef.value) return;
    await formRef.value.validate(async (valid) => {
        if (valid) {
            submitting.value = true;
            try {
                await createUser(form);
              ElMessage.success(t("users.userCreated"));
                dialogVisible.value = false;
                fetchUsers();
            } catch(e: any) {
              ElMessage.error(e.message || t("users.createUserFailed"));
            } finally {
                submitting.value = false;
            }
        }
    });
};

const showDeptDialog = (row: User) => {
    editingUserId.value = row.id;
    deptForm.deptId = row.deptId || '';
    deptForm.deptName = row.deptName || '';
    deptDialogVisible.value = true;
};

const submitDeptForm = async () => {
    if (!editingUserId.value) return;
    deptSubmitting.value = true;
    try {
        await updateUserDepartment(editingUserId.value, {
            deptId: deptForm.deptId,
            deptName: deptForm.deptName
        });
        ElMessage.success(t("users.departmentUpdated"));
        deptDialogVisible.value = false;
        fetchUsers();
    } catch (e: any) {
        ElMessage.error(e.message || t("users.departmentUpdateFailed"));
    } finally {
        deptSubmitting.value = false;
    }
};

const handleDelete = (row: User) => {
    ElMessageBox.confirm(
    t('users.confirmDeleteUser', { username: row.username }),
    t('common.warning'),
        {
      confirmButtonText: t('common.delete'),
      cancelButtonText: t('common.cancel'),
            type: 'warning',
        }
    ).then(async () => {
        try {
            await deleteUser(row.id);
      ElMessage.success(t("users.deletedSuccessfully"));
            fetchUsers();
        } catch(e: any) {
       ElMessage.error(e.message || t("users.deleteFailed"));
        }
    });
};

const handleResetPassword = (row: User) => {
   ElMessageBox.prompt(t('users.inputNewPassword'), t('users.resetPwd'), {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
        inputType: 'password',
      }).then(async ({ value }) => {
          if(!value) return;
          try {
              await resetUserPassword(row.id, value);
        ElMessage.success(t('users.passwordResetSuccess', { username: row.username }));
          } catch(e: any) {
        ElMessage.error(e.message || t('users.passwordResetFailed'));
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
