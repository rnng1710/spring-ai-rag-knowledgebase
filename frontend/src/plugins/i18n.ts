import { createI18n } from "vue-i18n";
import en from "../locales/en";
import zhCN from "../locales/zh-CN";

export const LOCALE_STORAGE_KEY = "app_locale";
export const SUPPORTED_LOCALES = ["zh-CN", "en"] as const;
export type AppLocale = (typeof SUPPORTED_LOCALES)[number];

const messages = {
  "zh-CN": zhCN,
  en
};

const normalizeLocale = (locale: string | null): AppLocale => {
  if (!locale) {
    return "zh-CN";
  }
  if (locale.startsWith("zh")) {
    return "zh-CN";
  }
  if (locale.startsWith("en")) {
    return "en";
  }
  return "zh-CN";
};

const initialLocale = normalizeLocale(localStorage.getItem(LOCALE_STORAGE_KEY));

const i18n = createI18n({
  legacy: false,
  locale: initialLocale,
  fallbackLocale: "zh-CN",
  messages,
  globalInjection: true
});

export const setAppLocale = (locale: AppLocale) => {
  i18n.global.locale.value = locale;
  localStorage.setItem(LOCALE_STORAGE_KEY, locale);
};

export const getAppLocale = (): AppLocale => normalizeLocale(i18n.global.locale.value as string);

export default i18n;
