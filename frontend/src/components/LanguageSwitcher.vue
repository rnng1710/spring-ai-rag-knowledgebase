<template>
  <el-select
    :model-value="currentLocale"
    size="small"
    style="width: 120px"
    @change="onLocaleChange"
  >
    <el-option :label="t('common.langZh')" value="zh-CN" />
    <el-option :label="t('common.langEn')" value="en" />
  </el-select>
</template>

<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { getAppLocale, setAppLocale, type AppLocale } from "../plugins/i18n";

const { t } = useI18n();

const currentLocale = computed(() => getAppLocale());

const onLocaleChange = (locale: AppLocale) => {
  setAppLocale(locale);
};
</script>
