import { describe, it, expect, beforeEach, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';

/**
 * `v-perm` 指令测试 —— 对应需求里的「无权限的按钮不再渲染，按权限分别渲染」。
 *
 * 两条断言口径刻意写死：
 *   1. 判据是 **DOM 里有没有这个节点**，不是 `display:none` / `visibility:hidden`。
 *      隐藏但仍存在的越权按钮能被 devtools 点开，这是"渲染层假安全"的典型。
 *   2. 一个组件里**不同按钮按各自的能力码**分别渲染 —— 只验"整块显示/整块隐藏"
 *      是验不出"按权限分别渲染"的。
 */

vi.mock('@/api', () => ({
  authApi: { login: vi.fn(), logout: vi.fn(), capabilities: vi.fn() },
  userApi: { me: vi.fn() },
}));

// eslint-disable-next-line import/first
import perm from '@/directives/permission';
// eslint-disable-next-line import/first
import { useUserStore } from '@/stores/user';

const Comp = {
  template: `
    <div>
      <button class="create" v-perm="'problem:create'">新建题目</button>
      <button class="edit" v-perm="['problem:edit', 'problem:manage']">编辑</button>
      <button class="always">查看</button>
      <button class="none" v-perm="">无码即放行</button>
    </div>`,
};

function render(codes) {
  const store = useUserStore();
  store.capabilities = codes === null
    ? null
    : { role: 9, roleLabel: '测试', home: '/problems', menus: [], perms: codes.map((c) => ({ code: c })) };
  return mount(Comp, { global: { directives: { perm } } });
}

beforeEach(() => {
  setActivePinia(createPinia());
});

describe('v-perm 指令', () => {
  it('有权限 → 正常渲染', () => {
    const w = render(['problem:create']);
    expect(w.find('.create').exists()).toBe(true);
  });

  it('无权限 → 节点从 DOM 中移除（不是隐藏）', () => {
    const w = render(['problem:manage']);
    expect(w.find('.create').exists()).toBe(false);
    expect(w.html()).not.toContain('新建题目');
  });

  it('同一组件内按各自的能力码分别渲染', () => {
    // 只有 create：新建在、编辑不在
    const onlyCreate = render(['problem:create']);
    expect(onlyCreate.find('.create').exists()).toBe(true);
    expect(onlyCreate.find('.edit').exists()).toBe(false);

    // 只有 edit：反过来
    const onlyEdit = render(['problem:edit']);
    expect(onlyEdit.find('.create').exists()).toBe(false);
    expect(onlyEdit.find('.edit').exists()).toBe(true);

    // 命中数组里任意一个即可
    const viaManage = render(['problem:manage']);
    expect(viaManage.find('.edit').exists()).toBe(true);

    // 两个都没有 → 两个都不渲染
    const neither = render(['problem:view']);
    expect(neither.find('.create').exists()).toBe(false);
    expect(neither.find('.edit').exists()).toBe(false);
  });

  it('不受限的按钮永远渲染（指令不能误伤）', () => {
    const w = render([]);
    expect(w.find('.always').exists()).toBe(true);
  });

  it('能力画像缺失（加载失败/未登录）→ 受限按钮全部不渲染，fail-closed', () => {
    const w = render(null);
    expect(w.find('.create').exists()).toBe(false);
    expect(w.find('.edit').exists()).toBe(false);
    expect(w.find('.always').exists()).toBe(true);
  });

  it('空值不构成权限门槛（undefined / 空字符串一律放行）', () => {
    const w = render([]);
    expect(w.find('.none').exists()).toBe(true);
  });
});
