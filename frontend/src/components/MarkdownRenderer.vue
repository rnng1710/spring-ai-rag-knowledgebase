<template>
  <div class="markdown-body" v-html="renderedHtml" @click="handleClick"></div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import MarkdownIt from 'markdown-it';
import hljs from 'highlight.js';
import 'highlight.js/styles/github-dark.css';
import { ElMessage } from 'element-plus';

const props = defineProps<{
  content: string;
  sources?: any[];
}>();

const emit = defineEmits(['source-click']);

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

md.core.ruler.push('citations', (state) => {
  const sources = props.sources || [];
  for (let i = 0; i < state.tokens.length; i++) {
    const token = state.tokens[i];
    if (token.type === 'paragraph_open') {
      const inlineToken = state.tokens[i + 1];
      if (inlineToken && inlineToken.type === 'inline') {
        const children = inlineToken.children;
        if (!children || children.length === 0) continue;

        let citeMatch = null;
        for (let j = children.length - 1; j >= 0; j--) {
          if (children[j].type === 'text') {
            const text = children[j].content;
            const match = text.match(/(《([^》]+)》(?:第\s*(\d+)\s*页|片段\s*(\d+)))[\s。.]*$/);
            if (match) {
              citeMatch = { index: j, match, text };
              break;
            }
          }
        }

        if (citeMatch) {
          const match = citeMatch.match;
          const fullCite = match[1];
          const filename = match[2];
          const pageNum = match[3] || match[4];

          // Determine if we need to keep the period
          const hadPeriod = citeMatch.text.endsWith('。') || citeMatch.text.endsWith('.');
          children[citeMatch.index].content = citeMatch.text.substring(0, match.index) + (hadPeriod ? '。' : '');

          const matchedSource = sources.find(s => {
            const sFile = s.file_name || s.fileName || s.source || '';
            const sPage = String(s.page_number ?? s.pageNumber ?? '');
            return sFile.includes(filename) && (sPage === pageNum || sPage.includes(pageNum));
          });

          const docUuid = matchedSource ? (matchedSource.doc_uuid || matchedSource.docUuid) : '';

          token.attrPush(['class', 'cited-paragraph']);
          token.attrPush(['data-tooltip', fullCite]);
          if (docUuid) {
            token.attrPush(['data-doc-uuid', docUuid]);
            token.attrPush(['data-page', pageNum]);
          }
        }
      }
    }
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
  // URI 编码后存入 data 属性：代码含 HTML 特殊字符（<、>、&、"等），直接嵌入会破坏 DOM 结构
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

const handleClick = async (event: MouseEvent) => {
  const target = event.target as HTMLElement;
  
  // Handle citation click
  const citedPara = target.closest('.cited-paragraph') as HTMLElement;
  if (citedPara) {
    const docUuid = citedPara.getAttribute('data-doc-uuid');
    const pageNumber = citedPara.getAttribute('data-page');
    if (docUuid) {
      emit('source-click', {
        docUuid: docUuid,
        pageNumber: pageNumber ? Number(pageNumber) : undefined
      });
      return;
    }
  }

  // Handle copy button click
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

/* Cited paragraph styles */
.cited-paragraph {
  position: relative;
  cursor: pointer;
  transition: all 0.3s ease;
  border-radius: 4px;
}

.cited-paragraph:hover {
  opacity: 0.85;
  text-decoration: underline dashed var(--el-color-primary, #409eff);
  text-underline-offset: 4px;
  background-color: rgba(64, 158, 255, 0.05); /* Slight bright background using element-plus primary color with low opacity */
}

.cited-paragraph:hover::after {
  content: attr(data-tooltip);
  position: absolute;
  bottom: 100%;
  left: 50%;
  transform: translateX(-50%) translateY(-8px);
  background-color: #303133;
  color: #fff;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 13px;
  white-space: nowrap;
  z-index: 1000;
  box-shadow: 0 2px 12px 0 rgba(0, 0, 0, 0.1);
  pointer-events: none;
  font-style: normal;
  text-decoration: none;
  opacity: 1;
}

/* Tooltip triangle */
.cited-paragraph:hover::before {
  content: '';
  position: absolute;
  bottom: 100%;
  left: 50%;
  transform: translateX(-50%) translateY(2px);
  border-width: 5px;
  border-style: solid;
  border-color: #303133 transparent transparent transparent;
  z-index: 1000;
  pointer-events: none;
}
</style>
