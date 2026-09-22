import MarkdownIt from 'markdown-it';
import hljs from '@/utils/highlight';
import DOMPurify from 'dompurify';
import 'highlight.js/styles/github-dark.css';

/**
 * Markdown 渲染（题面 / 题解 / AI 点评正文都用它）
 *
 * ⚠️ 必须消毒：AI 点评正文来自 LLM，是**不可信内容**。
 * 直接 v-html 注入等于把 XSS 的口子开在"别人给你的文本"上
 * （提示注入 + HTML 注入的组合拳）。
 * DOMPurify 默认会剥掉 script/on* 事件/iframe/javascript: 协议。
 *
 * 代码高亮走 highlight.js；未指定语言时用 highlightAuto（自动识别），
 * 失败则回退为纯文本转义输出。
 */

const md = new MarkdownIt({
  html: false, // 不允许原始 HTML —— 双重保险（DOMPurify 之外的第一道闸）
  linkify: true,
  breaks: true,
  highlight(str, lang) {
    if (lang && hljs.getLanguage(lang)) {
      try {
        return hljs.highlight(str, { language: lang, ignoreIllegals: true }).value;
      } catch {
        /* 落到自动识别 */
      }
    }
    try {
      return hljs.highlightAuto(str).value;
    } catch {
      return md.utils.escapeHtml(str);
    }
  },
});

// 外链统一新窗口打开（判题平台题面里常带参考链接，留在本页会丢编辑中的代码）
const defaultLinkOpen =
  md.renderer.rules.link_open ||
  function (tokens, idx, options, _env, self) {
    return self.renderToken(tokens, idx, options);
  };
md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
  tokens[idx].attrSet('target', '_blank');
  tokens[idx].attrSet('rel', 'noopener noreferrer');
  return defaultLinkOpen(tokens, idx, options, env, self);
};

/** Markdown 文本 → 已消毒的 HTML 字符串 */
export function renderMarkdown(text) {
  if (!text) return '';
  const raw = md.render(String(text));
  return DOMPurify.sanitize(raw, {
    ADD_ATTR: ['target', 'rel'],
    // 允许 hljs 输出的 class（否则高亮样式全丢）
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class', 'align'],
  });
}

/** 只渲染行内 Markdown（表格单元格等场景，避免块级元素撑破布局） */
export function renderInline(text) {
  if (!text) return '';
  return DOMPurify.sanitize(md.renderInline(String(text)), {
    ALLOWED_ATTR: ['href', 'title', 'target', 'rel', 'class'],
  });
}
