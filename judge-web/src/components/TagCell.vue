<template>
  <div v-if="list.length" class="cj-cell-tags" :class="{ 'cj-cell-tags--end': align === 'end' }">
    <el-tag
      v-for="t in visible"
      :key="t.id"
      size="small"
      effect="plain"
      type="info"
      class="cj-cell-tags__tag"
    >
      {{ t.name }}
    </el-tag>
    <!-- 超出部分折成 +N，并在 title 里给出全量标签名：
         列表页没有展开交互，但悬停要看得到"被折掉的是什么" -->
    <span v-if="overflowCount" class="cj-num cj-cell-tags__more" :title="hiddenNames">
      +{{ overflowCount }}
    </span>
  </div>
  <span v-else class="cj-dim">—</span>
</template>

<script setup>
import { computed } from 'vue';

/**
 * 标签单元格 —— 列表页唯一的标签渲染入口。
 *
 * <p>存在的理由：同一段"截断成 N 个 + 折成 +N"的逻辑此前在题库列表、题目管理、
 * 竞赛列表里各写了一遍，三份实现必然漂移（一处 slice(0,2)、一处不截断直接换行）。
 * 换行那一处正是"标签把行高撑开、与相邻行对不齐"的直接成因。
 *
 * <p>样式全部来自全局 data-list.css 的 `.cj-cell-tags`：
 * 单行 nowrap + overflow hidden + min-height 20px（与 el-tag--small 等高），
 * 因此**有标签的行与无标签的行行高一致**。
 */
const props = defineProps({
  /** 标签数组，元素需含 id 与 name */
  tags: { type: Array, default: () => [] },
  /** 最多显示几个，其余折成 +N */
  limit: { type: Number, default: 2 },
  /**
   * 主轴对齐：`'start'`（默认）| `'end'`。
   *
   * <p>列表页的「数据列」统一右对齐，标签列也必须跟上 —— 但标签用一个 flex 行装，
   * **flex 容器不响应 `text-align`**，只给 el-table-column 加 `align="right"`
   * 是推不动标签的，必须由本组件改主轴对齐。所以列定义里 `align="right"` 与
   * 这里的 `align="end"` 必须成对出现。
   */
  align: { type: String, default: 'start' },
});

const list = computed(() => (Array.isArray(props.tags) ? props.tags.filter(Boolean) : []));
const visible = computed(() => list.value.slice(0, props.limit));
const overflowCount = computed(() => Math.max(0, list.value.length - props.limit));
const hiddenNames = computed(() =>
  list.value
    .slice(props.limit)
    .map((t) => t.name)
    .join('、'),
);
</script>

<style scoped>
/* 标签自身不做换行；列宽的裁切交给父容器（.cj-cell-tags 的 overflow:hidden），
   这样截断点由列宽决定，而不是由标签文字长度决定 */
.cj-cell-tags__tag {
  flex: 0 0 auto;
  max-width: 100%;
}
</style>
