<template>
  <div class="markdown-body" v-html="renderedHtml" @click="handleCopyClick"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import 'highlight.js/styles/github-dark.css';
import { ElMessage } from 'element-plus';

const props = defineProps<{
  content: string;
}>();

const md = new MarkdownIt({
  html: false, // Disable raw HTML for security
  linkify: true,
  typographer: true,
  highlight: function (str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(str, { language: lang, ignoreIllegals: true }).value;
      } catch (__) {}
    }
    return md.utils.escapeHtml(str);
  }
});

// Override fence rule to add wrapper and copy button
const defaultRender = md.renderer.rules.fence || function (tokens, idx, options, env, self) {
  return self.renderToken(tokens, idx, options);
};

md.renderer.rules.fence = function (tokens, idx, options, env, self) {
  const token = tokens[idx];
  const lang = token.info.trim();
  
  // We need the raw content for copying
  const rawCode = token.content;
  // Encode safely for data attribute
  const encodedCode = encodeURIComponent(rawCode); 
  
  const highlighted = defaultRender(tokens, idx, options, env, self);
  
  return `
    <div class="code-block-wrapper">
      <div class="code-block-header">
        <span class="code-block-lang">${lang || 'text'}</span>
        <button class="code-block-copy-btn" data-code="${encodedCode}">
          <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round" class="css-i6dzq1"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>
          Copy
        </button>
      </div>
      ${highlighted}
    </div>
  `;
};

const renderedHtml = computed(() => {
  return md.render(props.content || '');
});

const handleCopyClick = async (event: MouseEvent) => {
  const target = event.target as HTMLElement;
  const copyBtn = target.closest('.code-block-copy-btn') as HTMLElement;
  
  if (copyBtn) {
    const rawCode = decodeURIComponent(copyBtn.getAttribute('data-code') || '');
    if (rawCode) {
      try {
        await navigator.clipboard.writeText(rawCode);
        ElMessage.success('Copied to clipboard');
        
        // Visual feedback
        const originalText = copyBtn.innerHTML;
        copyBtn.innerHTML = '<svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" stroke-width="2" fill="none" stroke-linecap="round" stroke-linejoin="round" class="css-i6dzq1"><polyline points="20 6 9 17 4 12"></polyline></svg> Copied';
        setTimeout(() => {
          copyBtn.innerHTML = originalText;
        }, 2000);
      } catch (err) {
        ElMessage.error('Failed to copy');
      }
    }
  }
};
</script>

<style>
/* Un-scoped CSS to apply to v-html injected content */
.markdown-body {
  color: var(--chat-text-primary);
  line-height: 1.6;
  font-size: 15px;
  word-wrap: break-word;
}

.markdown-body p {
  margin-top: 0;
  margin-bottom: 12px;
}
.markdown-body p:last-child {
  margin-bottom: 0;
}

.markdown-body h1, .markdown-body h2, .markdown-body h3, .markdown-body h4, .markdown-body h5, .markdown-body h6 {
  margin-top: 24px;
  margin-bottom: 12px;
  font-weight: 600;
  line-height: 1.25;
}

.markdown-body h1 { font-size: 1.5em; }
.markdown-body h2 { font-size: 1.3em; }
.markdown-body h3 { font-size: 1.1em; }

.markdown-body a {
  color: var(--chat-pill-text);
  text-decoration: none;
}
.markdown-body a:hover {
  text-decoration: underline;
}

.markdown-body ul, .markdown-body ol {
  padding-left: 2em;
  margin-top: 0;
  margin-bottom: 12px;
}
.markdown-body li {
  margin-bottom: 4px;
}

.markdown-body blockquote {
  margin: 0 0 12px;
  padding: 0 14px;
  color: var(--chat-text-secondary);
  border-left: 4px solid var(--chat-sidebar-border);
}

.markdown-body table {
  border-collapse: collapse;
  width: 100%;
  margin-bottom: 12px;
  overflow: auto;
  display: block;
}
.markdown-body table th, .markdown-body table td {
  padding: 8px 12px;
  border: 1px solid var(--chat-sidebar-border);
}
.markdown-body table th {
  font-weight: 600;
  background-color: var(--chat-action-bg);
}

.markdown-body img {
  max-width: 100%;
  box-sizing: content-box;
}

.markdown-body code {
  padding: 0.2em 0.4em;
  margin: 0;
  font-size: 85%;
  background-color: var(--chat-action-bg);
  border-radius: 6px;
  font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, Liberation Mono, monospace;
}

/* Avoid styles for code tags inside pre since highlight.js handles it */
.markdown-body pre code {
  padding: 0;
  background-color: transparent;
  border-radius: 0;
}

/* Code Block Wrapper Styles */
.code-block-wrapper {
  margin-bottom: 16px;
  border-radius: 8px;
  overflow: hidden;
  background-color: #0d1117; /* GitHub Dark background */
  border: 1px solid #30363d;
}

.code-block-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 16px;
  background-color: #161b22; /* GitHub Dark header */
  border-bottom: 1px solid #30363d;
  color: #8b949e;
  font-size: 12px;
  font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, Liberation Mono, monospace;
}

.code-block-lang {
  text-transform: lowercase;
}

.code-block-copy-btn {
  display: flex;
  align-items: center;
  gap: 4px;
  background: transparent;
  border: none;
  color: #8b949e;
  cursor: pointer;
  font-size: 12px;
  padding: 4px 8px;
  border-radius: 4px;
  transition: all 0.2s;
}

.code-block-copy-btn:hover {
  background-color: rgba(255, 255, 255, 0.1);
  color: #c9d1d9;
}

.code-block-wrapper pre {
  margin: 0;
  padding: 16px;
  overflow: auto;
}
</style>
