import { useUserStore } from '@/stores/user';

/**
 * 按钮级权限指令 `v-perm`。
 *
 * 用法：
 *   <el-button v-perm="'problem:create'">新建题目</el-button>
 *   <el-button v-perm="['problem:edit', 'problem:manage']">编辑</el-button>  // 任一命中即可
 *
 * 语义是**不渲染**（把元素从 DOM 移除），而不是 `display:none`：
 * 隐藏但仍存在于 DOM 的越权按钮，既能被 devtools 改成可见、也会被读屏软件念出来。
 * 既然后端总会在接口层再拦一次，前端就没必要留这个半吊子入口。
 *
 * ── 与后端的关系 ─────────────────────────────────────────────────────────────
 * 这里判断的是**后端下发的**能力码（`GET /accounts/me/capabilities`），
 * 前端不做任何「角色 → 能力」的推导。指令本身不是安全边界，只是渲染规则；
 * 真正的边界在 `@RequireRole` 与各服务的归属校验上。
 *
 * ── 使用前提（重要）──────────────────────────────────────────────────────────
 * 指令只在 `mounted` / `updated` 时求值一次：**没有权限就把节点删掉，之后再授权也回不来**。
 * 这在本项目里是成立的 —— 路由守卫在进入任何页面**之前**已经 `await loadProfile()`，
 * 而 `loadProfile` 内部会等能力画像返回，所以组件挂载时能力码一定已就绪。
 *
 * 若某处的显隐需要在运行期随能力变化（例如同一页面内切换身份），
 * 请改用 `v-if="user.can('xxx')"`（模板表达式是响应式的），而不要用本指令。
 * 表格列同理：`<el-table-column>` 必须用 `v-if`，直接删 DOM 会破坏表格列布局。
 */
function allowed(value) {
  const user = useUserStore();
  if (!value) {
    return true;
  }
  const codes = Array.isArray(value) ? value : [value];
  if (!codes.length) {
    return true;
  }
  return codes.some((code) => user.can(code));
}

function apply(el, binding) {
  if (allowed(binding.value)) {
    return;
  }
  el.parentNode?.removeChild(el);
}

export const perm = {
  mounted: apply,
  updated: apply,
};

export default perm;
