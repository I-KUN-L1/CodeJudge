<template>
  <span class="verdict" :class="cls">
    <span v-if="showDot" class="verdict__dot" />
    {{ text }}
  </span>
</template>

<script setup>
import { computed } from 'vue';
import { verdictLabel, verdictCls } from '@/utils/format';

/**
 * 判题结论标签。
 * 颜色口径集中在 utils/format.js（VERDICT 表），避免各页面各写一套导致同一个 verdict
 * 在列表页和详情页颜色不一致。
 */
const props = defineProps({
  verdict: { type: String, default: '' },
  /** 是否显示圆点：列表页显示更醒目，表格密集处可关掉 */
  dot: { type: Boolean, default: true },
  /** 未出结论时的占位文案（排队中/判题中） */
  pendingText: { type: String, default: '' },
});

const text = computed(() => {
  if (!props.verdict) return props.pendingText || '—';
  return verdictLabel(props.verdict);
});

const cls = computed(() => (props.verdict ? verdictCls(props.verdict) : 'cj-dim'));
const showDot = computed(() => props.dot && !!props.verdict);
</script>

<style scoped>
.verdict {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
}
.verdict__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  flex: 0 0 auto;
}
</style>
