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
          <el-input v-model="password" type="password" placeholder="password" />
        </el-form-item>
        <el-button type="primary" class="login-button" @click="login">Continue</el-button>
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
import { ensureAuth } from "../api/client";

const router = useRouter();
const role = ref<"user" | "admin">("user");
const username = ref("user");
const password = ref("password");

const login = () => {
  ensureAuth(username.value, password.value);
  localStorage.setItem("auth_role", role.value);
  router.push(role.value === "admin" ? "/admin/index" : "/user/chat");
};
</script>
