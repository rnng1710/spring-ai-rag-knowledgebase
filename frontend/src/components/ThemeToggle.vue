<template>
  <el-button 
    class="theme-toggle-btn"
    circle
    :icon="isDark ? Moon : Sunny"
    @click="toggleDark()"
    :title="isDark ? t('common.lightMode') : t('common.darkMode')"
  />
</template>

<script setup lang="ts">
import { useDark, useToggle } from '@vueuse/core';
import { Sunny, Moon } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';

const { t } = useI18n();

// useDark defaults to checking html.dark and localStorage 'vueuse-color-scheme'
const isDark = useDark({
  valueDark: 'dark',
  valueLight: 'light', // Optional: you can leave this empty if you only toggle .dark
});

const toggleDark = useToggle(isDark);
</script>

<style scoped>
.theme-toggle-btn {
  border: none;
  background-color: var(--chat-action-bg, rgba(255, 255, 255, 0.5));
  backdrop-filter: blur(8px);
  color: var(--chat-text-primary, #1f1f1f);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.05);
  transition: all 0.3s ease;
}

.theme-toggle-btn:hover {
  background-color: var(--chat-action-hover, rgba(255, 255, 255, 0.8));
  transform: scale(1.05);
}

html.dark .theme-toggle-btn {
  background-color: var(--chat-action-bg, rgba(255, 255, 255, 0.1));
  color: var(--chat-text-primary, #f3f4f6);
}

html.dark .theme-toggle-btn:hover {
  background-color: var(--chat-action-hover, rgba(255, 255, 255, 0.2));
}
</style>
