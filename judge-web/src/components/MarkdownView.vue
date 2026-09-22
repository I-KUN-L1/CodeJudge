<template>
  <div class="cj-md" v-html="html" />
</template>

<script setup>
import { computed } from 'vue';
import { renderMarkdown, renderInline } from '@/utils/markdown';

/**
 * Markdown 渲染容器。
 * 渲染与消毒逻辑集中在 utils/markdown.js —— 这里只负责把字符串变成 HTML。
 * 用 v-html 是必要的（要的就是 HTML），安全性由 DOMPurify 保证。
 */
const props = defineProps({
  text: { type: String, default: '' },
  inline: { type: Boolean, default: false },
});

const html = computed(() => (props.inline ? renderInline(props.text) : renderMarkdown(props.text)));
</script>
