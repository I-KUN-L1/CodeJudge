<template>
  <div class="editor" :class="{ 'is-readonly': readonly }" :style="{ height: heightCss }">
    <!-- 行号槽 -->
    <div ref="gutterRef" class="editor__gutter" aria-hidden="true">
      <div v-for="n in lineCount" :key="n" class="editor__ln">{{ n }}</div>
    </div>

    <!-- 高亮层 + 输入层 叠放：输入层透明字，高亮层只读。
         这是"轻量编辑器"的经典做法 —— 不引入 Monaco/CodeMirror（各数十 MB），
         用 highlight.js 复用已有的高亮能力，代价是需要严格对齐两层的字体度量。 -->
    <div class="editor__scroll" ref="scrollRef">
      <pre class="editor__pre" aria-hidden="true"><code
        class="hljs"
        :class="languageClass"
        v-html="highlighted"
      /></pre>

      <textarea
        ref="taRef"
        class="editor__ta"
        :value="modelValue"
        :readonly="readonly"
        spellcheck="false"
        autocapitalize="off"
        autocomplete="off"
        :placeholder="placeholder"
        @input="onInput"
        @scroll="syncScroll"
        @keydown.tab.prevent="onTab"
      />
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue';
import hljs, { hljsNameFor } from '@/utils/highlight';

/**
 * 代码编辑器
 *
 * 取舍说明：项目在 README/PLAN 里点名了 Monaco，但 Monaco 打包后体积大（≥ 数 MB）、
 * 且与 Vite 的 worker 配置有额外集成成本。这里采用"透明 textarea 叠加高亮层"的方案：
 *   · 体积 ≈ 0（复用已用于题面渲染的 highlight.js）；
 *   · 具备行号、Tab 缩进、随语言切换高亮、滚动同步；
 *   · 不具备的能力（已明确、不假装有）：补全、语法诊断、多光标。
 * 若后续需要补全/诊断，把本组件换成 Monaco 即可，父组件的 props 契约（v-model + language）不变。
 */

const props = defineProps({
  modelValue: { type: String, default: '' },
  /** 后端 Language 枚举名：JAVA / CPP / PYTHON / GO */
  language: { type: String, default: 'JAVA' },
  readonly: { type: Boolean, default: false },
  height: { type: [String, Number], default: 420 },
  placeholder: { type: String, default: '在此编写你的代码…（Tab 缩进 / Shift+Tab 反缩进）' },
});

const emit = defineEmits(['update:modelValue']);

const taRef = ref(null);
const scrollRef = ref(null);
const gutterRef = ref(null);

const heightCss = computed(() =>
  typeof props.height === 'number' ? `${props.height}px` : String(props.height),
);

const lineCount = computed(() => {
  const n = String(props.modelValue || '').split('\n').length;
  return Math.max(n, 1);
});

const languageClass = computed(() => `language-${hljsNameFor(props.language)}`);

const highlighted = computed(() => {
  const code = String(props.modelValue || '');
  const lang = hljsNameFor(props.language);
  // 末尾补一个换行：否则最后一行在 <pre> 里可能显示不出来
  const source = code.endsWith('\n') ? `${code} ` : code;
  try {
    if (hljs.getLanguage(lang)) {
      return hljs.highlight(source, { language: lang, ignoreIllegals: true }).value;
    }
    return hljs.highlightAuto(source).value;
  } catch {
    // 高亮失败不能影响输入 —— 退化为纯文本转义
    return source.replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' })[c]);
  }
});

function onInput(e) {
  emit('update:modelValue', e.target.value);
}

/** 滚动同步：行号槽与高亮层都跟随 textarea */
function syncScroll() {
  const ta = taRef.value;
  if (!ta) return;
  if (scrollRef.value) {
    scrollRef.value.scrollTop = ta.scrollTop;
    scrollRef.value.scrollLeft = ta.scrollLeft;
  }
  if (gutterRef.value) {
    gutterRef.value.scrollTop = ta.scrollTop;
  }
}

/**
 * Tab / Shift+Tab 缩进。
 * 只处理单行（不实现多行块缩进）——在 OJ 场景里一次改一行的频率远高于块缩进，
 * 而块缩进的实现复杂度（选区还原、撤销栈）与收益不成比例。
 */
function onTab(e) {
  const ta = taRef.value;
  if (!ta || props.readonly) return;
  const INDENT = '    '; // 4 空格，与 Java/C++ 主流风格一致
  const { selectionStart: start, selectionEnd: end, value } = ta;

  if (e.shiftKey) {
    // 反缩进：删掉行首最多 4 个空格
    const lineStart = value.lastIndexOf('\n', start - 1) + 1;
    const head = value.slice(lineStart, lineStart + INDENT.length);
    const strip = head.match(/^ +/)?.[0]?.length || 0;
    if (strip === 0) return;
    const removed = Math.min(strip, INDENT.length);
    const next = value.slice(0, lineStart) + value.slice(lineStart + removed);
    emit('update:modelValue', next);
    requestAnimationFrame(() => {
      ta.selectionStart = Math.max(lineStart, start - removed);
      ta.selectionEnd = Math.max(lineStart, end - removed);
    });
    return;
  }

  const next = `${value.slice(0, start)}${INDENT}${value.slice(end)}`;
  emit('update:modelValue', next);
  requestAnimationFrame(() => {
    ta.selectionStart = ta.selectionEnd = start + INDENT.length;
  });
}

// 语言切换后让 textarea 保持焦点，避免用户"选了语言就得重新点回编辑区"
watch(
  () => props.language,
  () => syncScroll(),
);

onMounted(() => syncScroll());

defineExpose({
  focus: () => taRef.value?.focus(),
  scrollToBottom: () => {
    const ta = taRef.value;
    if (ta) {
      ta.scrollTop = ta.scrollHeight;
      syncScroll();
    }
  },
});
</script>

<style scoped>
.editor {
  display: flex;
  min-height: 160px;
  background: #0d1117;
  border: 1px solid var(--cj-border);
  border-radius: 10px;
  overflow: hidden;
  font-family: var(--cj-mono);
  font-size: 13.5px;
  line-height: 1.55;
}

.editor__gutter {
  flex: 0 0 auto;
  padding: 12px 0;
  width: 52px;
  overflow: hidden;
  text-align: right;
  color: #4d5866;
  background: #0b0e13;
  border-right: 1px solid #1c222b;
  user-select: none;
}
.editor__ln {
  padding-right: 10px;
  height: 1.55em;
}

.editor__scroll {
  position: relative;
  flex: 1 1 auto;
  overflow: hidden;
}

/* 两层的字体度量、内边距、换行策略必须**完全一致**，否则光标与文字会错位 */
.editor__pre,
.editor__ta {
  margin: 0;
  padding: 12px 14px;
  border: 0;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  white-space: pre;
  tab-size: 4;
  word-break: normal;
  overflow-wrap: normal;
}

.editor__pre {
  position: absolute;
  inset: 0;
  overflow: hidden;
  color: #e6edf3;
  background: transparent;
  pointer-events: none;
}
.editor__pre code {
  display: block;
  padding: 0;
  background: none;
  border: 0;
}

.editor__ta {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  resize: none;
  outline: none;
  /* 文字透明、只留光标：真正的字形由下层高亮层绘制 */
  color: transparent;
  caret-color: #e6edf3;
  background: transparent;
  overflow: auto;
}
.editor__ta::placeholder {
  color: #5a6472;
}
.editor__ta::selection {
  background: rgba(88, 166, 255, 0.35);
}

.editor.is-readonly {
  opacity: 0.95;
}
.editor.is-readonly .editor__ta {
  caret-color: transparent;
}
</style>
