<template>
  <el-container class="admin-layout">
    <el-aside width="240px" class="admin-aside">
      <div class="logo">
        <h2>RAG Admin</h2>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="admin-menu"
        router
        text-color="#b8b8b8"
        active-text-color="#fff"
        background-color="#1e1e1e"
      >
        <el-menu-item index="/admin/index">
          <el-icon><Odometer /></el-icon>
          <span>Dashboard</span>
        </el-menu-item>
        <el-menu-item index="/admin/documents">
          <el-icon><Document /></el-icon>
          <span>Documents</span>
        </el-menu-item>
      </el-menu>
      
      <div class="aside-footer">
        <el-button type="text" @click="logout" class="logout-btn">
             <el-icon><SwitchButton /></el-icon> Sign Out
        </el-button>
      </div>
    </el-aside>
    
    <el-container>
        <el-header class="admin-header">
            <div class="header-left">
                <!-- Breadcrumbs could go here -->
            </div>
      <div class="header-right">
        <el-dropdown trigger="click" @command="handleCommand">
          <span class="el-dropdown-link" style="cursor: pointer; display: flex; align-items: center;">
            <span style="margin-right:12px">Admin</span>
            <el-avatar :size="32" icon="UserFilled" />
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">Sign out</el-dropdown-item>
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
import { clearTokens } from "../api/client";
import { Odometer, Document, SwitchButton, UserFilled } from '@element-plus/icons-vue';

const route = useRoute();
const router = useRouter();

const activeMenu = computed(() => route.path);

const logout = () => {
  clearTokens();
  localStorage.removeItem("auth_user");
  localStorage.removeItem("auth_role");
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
  background-color: #f5f7fa;
}

.admin-aside {
  background-color: #1e1e1e;
  color: #fff;
  display: flex;
  flex-direction: column;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-bottom: 1px solid #333;
}
.logo h2 {
    color: #fff;
    margin: 0;
    font-size: 18px;
}

.admin-menu {
  border-right: none;
  flex: 1;
}

.admin-menu :deep(.el-menu-item):hover {
    background-color: #2c2c2c !important;
}
.admin-menu :deep(.el-menu-item.is-active) {
    background-color: #409eff !important;
}

.aside-footer {
    padding: 20px;
    border-top: 1px solid #333;
    text-align: center;
}
.logout-btn {
    color: #999;
}
.logout-btn:hover {
    color: #fff;
}

.admin-header {
    background: #fff;
    border-bottom: 1px solid #e6e6e6;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 0 24px;
    height: 60px;
}

.admin-main {
    padding: 24px;
    overflow-y: auto;
}
</style>
