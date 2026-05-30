<template>
  <el-container class="admin-layout">
    <el-aside width="280px" class="admin-aside">
      <div class="logo">
        <h2>{{ t("common.appNameShort") }}</h2>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="admin-menu"
        router
        text-color="var(--chat-text-secondary)"
        active-text-color="var(--accent)"
        background-color="transparent"
      >
        <el-menu-item index="/admin/index">
          <el-icon><Odometer /></el-icon>
          <span>{{ t("adminLayout.dashboard") }}</span>
        </el-menu-item>
        <el-menu-item index="/admin/documents">
          <el-icon><Document /></el-icon>
          <span>{{ t("adminLayout.documents") }}</span>
        </el-menu-item>
        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon>
          <span>{{ t("adminLayout.users") }}</span>
        </el-menu-item>
        <el-menu-item index="/admin/evaluation">
          <el-icon><DataAnalysis /></el-icon>
          <span>{{ t("adminLayout.evaluation") }}</span>
        </el-menu-item>
      </el-menu>
      
      <div class="aside-footer">
        <el-button type="text" @click="logout" class="logout-btn">
             <el-icon><SwitchButton /></el-icon> {{ t("common.signOut") }}
        </el-button>
      </div>
    </el-aside>
    
    <el-container>
        <el-header class="admin-header">
            <div class="header-left">
                <!-- Breadcrumbs could go here -->
            </div>
      <div class="header-right">
        <ThemeToggle style="margin-right: 16px;" />
        <LanguageSwitcher />
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="el-dropdown-link" style="cursor: pointer; display: flex; align-items: center; margin-left: 12px;">
            <span style="margin-right:12px">{{ username }}</span>
            <el-avatar :size="32" icon="UserFilled" />
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">{{ t("common.signOut") }}</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
        </el-header>
        <el-main class="admin-main">
            <router-view />
        </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useRoute, useRouter } from "vue-router";
import { Odometer, Document, SwitchButton, UserFilled, User, DataAnalysis } from '@element-plus/icons-vue';
import { clearAuthSession, getAccessToken, getUsernameFromAccessToken } from "../utils/auth";
import { useI18n } from "vue-i18n";
import LanguageSwitcher from "../components/LanguageSwitcher.vue";
import ThemeToggle from "../components/ThemeToggle.vue";

const route = useRoute();
const router = useRouter();
const { t } = useI18n();

const activeMenu = computed(() => route.path);
const username = computed(() => getUsernameFromAccessToken(getAccessToken()) || localStorage.getItem("auth_user") || "admin");

const logout = () => {
  clearAuthSession();
  router.push("/login");
};

const handleCommand = (command: string) => {
  if (command === 'logout') {
    logout();
  }
};
</script>

<style scoped>
.admin-layout {
  height: 100vh;
  width: 100vw;
  background-color: var(--bg, #f5f7fa);
}

.admin-aside {
  background-color: var(--chat-sidebar-bg, #f0f4f9);
  color: var(--chat-text-primary, #1f1f1f);
  display: flex;
  flex-direction: column;
}

.logo {
  height: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid var(--border, #333);
}
.logo h2 {
    color: var(--chat-text-primary, #1f1f1f);
    margin: 0;
    font-size: 20px;
    font-weight: 600;
}

.admin-menu {
  border-right: none;
  flex: 1;
}

.admin-menu :deep(.el-menu-item):hover {
    background-color: var(--chat-action-hover) !important;
}
.admin-menu :deep(.el-menu-item.is-active) {
    background-color: var(--chat-action-bg) !important;
    font-weight: 600;
}

.admin-menu :deep(.el-menu-item) {
    font-size: 15px;
    height: 56px;
    line-height: 56px;
    margin: 4px 12px;
    border-radius: 8px;
}

.aside-footer {
    padding: 20px;
    border-top: 1px solid var(--border, #333);
    text-align: center;
}
.logout-btn {
    color: #999;
}
.logout-btn:hover {
    color: #fff;
}

.admin-header {
    background: var(--card, #fff);
    border-bottom: 1px solid var(--border, #e6e6e6);
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 32px;
    height: 72px;
}

.admin-main {
    padding: 24px;
    overflow-y: auto;
}
</style>
