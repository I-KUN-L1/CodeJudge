<template>
  <div ref="el" class="cj-chart" :class="{ 'cj-chart--tall': tall }" />
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';
import * as echarts from 'echarts';
import { useThemeStore } from '@/stores/theme';

/**
 * ECharts 容器
 *
 * 两个必须处理的点：
 *  1. **容器必须有显式高度**，否则高度为 0，图表不渲染（ECharts 的经典坑）。
 *     高度由 .cj-chart / .cj-chart--tall 提供，不要在这个 div 上写 height:100%。
 *  2. **主题切换要重建实例**：ECharts 的暗色主题在初始化时固化，改 option 无效，
 *     必须 dispose 后重新 init（并重新 setOption）。
 */

const props = defineProps({
  option: { type: Object, required: true },
  tall: { type: Boolean, default: false },
});

const el = ref(null);
const theme = useThemeStore();
let chart = null;

function buildOption() {
  // 把当前主题的 CSS 变量喂给 ECharts（它不认识 CSS 变量）
  const styles = getComputedStyle(document.documentElement);
  const textColor = styles.getPropertyValue('--cj-text-sub').trim() || '#5c6472';
  const axisLine = styles.getPropertyValue('--cj-border').trim() || '#e3e7ee';
  const palette = chartPalette(styles);

  return {
    backgroundColor: 'transparent',
    textStyle: { color: textColor, fontFamily: 'inherit' },
    // 全局系列调色板：由 CSS 变量提供（视图不再硬编码色值）。
    // 放在 ...props.option **之前**，视图若显式指定 color 仍以视图为准
    ...(palette.length ? { color: palette } : {}),
    ...props.option,
    xAxis: props.option.xAxis
      ? Array.isArray(props.option.xAxis)
        ? props.option.xAxis.map(mergeAxis)
        : mergeAxis(props.option.xAxis)
      : undefined,
    yAxis: props.option.yAxis
      ? Array.isArray(props.option.yAxis)
        ? props.option.yAxis.map(mergeAxis)
        : mergeAxis(props.option.yAxis)
      : undefined,
  };

  function mergeAxis(axis) {
    return {
      axisLine: { lineStyle: { color: axisLine } },
      axisLabel: { color: textColor },
      splitLine: { lineStyle: { color: axisLine, type: 'dashed' } },
      ...axis,
    };
  }
}

/**
 * 读 CSS 里的图表系列色（--chart-1..4）。
 *
 * 为什么不把色值写成本模块的常量：只有浏览器知道 `html.dark` 最终让哪一条声明胜出。
 * 主题切换时本组件会 dispose + rebuild（见下方 watch），届时重新计算才会拿到新值。
 */
function chartPalette(styles) {
  const out = [];
  for (const key of ['--chart-1', '--chart-2', '--chart-3', '--chart-4']) {
    const v = styles.getPropertyValue(key).trim();
    if (v) out.push(v);
  }
  return out;
}

function render() {
  if (!chart) return;
  chart.setOption(buildOption(), true);
}

function rebuild() {
  if (chart) {
    chart.dispose();
    chart = null;
  }
  if (!el.value) return;
  chart = echarts.init(el.value, null, { renderer: 'canvas' });
  render();
}

let resizeObserver = null;

onMounted(() => {
  rebuild();
  // 用 ResizeObserver 而不是 window.resize：侧栏折叠、抽屉打开等布局变化
  // 不会触发 window.resize，图表会一直保持旧宽度
  if (typeof ResizeObserver !== 'undefined' && el.value) {
    resizeObserver = new ResizeObserver(() => chart?.resize());
    resizeObserver.observe(el.value);
  }
  window.addEventListener('resize', onWinResize);
});

function onWinResize() {
  chart?.resize();
}

watch(() => props.option, render, { deep: true });
watch(() => theme.mode, rebuild);

onBeforeUnmount(() => {
  window.removeEventListener('resize', onWinResize);
  resizeObserver?.disconnect();
  chart?.dispose();
  chart = null;
});
</script>
