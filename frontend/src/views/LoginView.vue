<template>
  <div class="login-shell">
    <div class="login-card">
      <div class="login-top-controls" style="display: flex; gap: 8px; justify-content: flex-end; width: 100%; margin-bottom: 12px;">
        <ThemeToggle />
        <LanguageSwitcher />
      </div>
      <div class="login-header">
        <div class="login-mark"></div>
        <div>
          <h1>{{ t("common.appName") }}</h1>
          <div class="login-sub">{{ t("common.signInHint") }}</div>
        </div>
      </div>
      <el-form label-position="top" class="login-form">
        <el-form-item :label="t('common.username')">
          <el-input v-model="username" :placeholder="t('auth.userPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('common.password')">
          <el-input v-model="password" type="password" :placeholder="t('auth.passwordPlaceholder')" @keyup.enter="login" />
        </el-form-item>
        <el-button type="primary" class="login-button" :loading="loading" @click="login">{{ t("common.continue") }}</el-button>
      </el-form>
      <div class="login-footer">
        {{ t("auth.loginFooter") }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { loginApi, setTokens } from "../api/client";
import { ElMessage } from 'element-plus';
import { clearAuthSession, getRoleFromAccessToken, getUsernameFromAccessToken } from "../utils/auth";
import { useI18n } from "vue-i18n";
import LanguageSwitcher from "../components/LanguageSwitcher.vue";
import ThemeToggle from "../components/ThemeToggle.vue";

const router = useRouter();
const { t } = useI18n();
const username = ref("");
const password = ref("");
const loading = ref(false);

const login = async () => {
    if(!username.value || !password.value) {
        ElMessage.warning(t("auth.needCredentials"));
        return;
    }

    loading.value = true;
    try {
        const tokens = await loginApi(username.value, password.value);
        setTokens(tokens.access_token, tokens.refresh_token);

        const resolvedRole = getRoleFromAccessToken(tokens.access_token);
        const resolvedUsername = getUsernameFromAccessToken(tokens.access_token);
        if (!resolvedRole || !resolvedUsername) {
            clearAuthSession();
          throw new Error(t("auth.invalidToken"));
        }

        localStorage.setItem("auth_user", resolvedUsername);
        
        ElMessage.success(t("auth.loginSuccess"));
        router.push(resolvedRole === "ADMIN" ? "/admin/index" : "/user/chat");
    } catch (e: any) {
        console.error(e);
        ElMessage.error(e.message || t("auth.loginFailed"));
    } finally {
        loading.value = false;
    }
};
</script>
