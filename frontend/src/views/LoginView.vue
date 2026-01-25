<template>
  <div class="login-shell">
    <div class="login-card">
      <div class="login-header">
        <div class="login-mark"></div>
        <div>
          <h1>Campus Knowledge Base</h1>
          <div class="login-sub">Sign in to continue</div>
        </div>
      </div>
      <el-form label-position="top" class="login-form">
        <el-form-item label="Role">
          <el-radio-group v-model="role">
            <el-radio label="user">User</el-radio>
            <el-radio label="admin">Admin</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="Username">
          <el-input v-model="username" placeholder="user" />
        </el-form-item>
        <el-form-item label="Password">
          <el-input v-model="password" type="password" placeholder="password" @keyup.enter="login" />
        </el-form-item>
        <el-button type="primary" class="login-button" :loading="loading" @click="login">Continue</el-button>
      </el-form>
      <div class="login-footer">
        User: chat with the knowledge base. Admin: manage indexing and uploads.
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { loginApi, setTokens } from "../api/client";
import { ElMessage } from 'element-plus';

const router = useRouter();
const role = ref<"user" | "admin">("user");
const username = ref("user");
const password = ref("password");
const loading = ref(false);

const login = async () => {
    if(!username.value || !password.value) {
        ElMessage.warning("请输入用户名和密码");
        return;
    }

    loading.value = true;
    try {
        const tokens = await loginApi(username.value, password.value);
        setTokens(tokens.access_token, tokens.refresh_token);
        localStorage.setItem("auth_role", role.value);
        localStorage.setItem("auth_user", username.value); // For display
        
        ElMessage.success("登录成功");
        router.push(role.value === "admin" ? "/admin/index" : "/user/chat");
    } catch (e: any) {
        console.error(e);
        ElMessage.error(e.message || "登录失败");
    } finally {
        loading.value = false;
    }
};
</script>
