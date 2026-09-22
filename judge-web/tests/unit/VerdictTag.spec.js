import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import VerdictTag from '@/components/VerdictTag.vue';

/**
 * 组件层冒烟测试。
 *
 * 只挑最"小"但被所有列表/详情页复用的结论标签：它的颜色与文案口径必须和
 * utils/format.js 完全一致，否则会出现"列表页绿色、详情页红色"这种自相矛盾的展示。
 */
describe('VerdictTag', () => {
  it('已知结论显示中文并可带强调圆点', () => {
    const w = mount(VerdictTag, { props: { verdict: 'AC' } });
    expect(w.text()).toBe('通过');
    expect(w.classes()).toContain('v-ac');
    expect(w.find('.verdict__dot').exists()).toBe(true);
  });

  it('dot=false 时不渲染圆点（表格密集处用）', () => {
    const w = mount(VerdictTag, { props: { verdict: 'WA', dot: false } });
    expect(w.text()).toBe('答案错误');
    expect(w.find('.verdict__dot').exists()).toBe(false);
  });

  it('未出结论时用调用方给的占位文案，且不显示圆点、不着色', () => {
    const w = mount(VerdictTag, { props: { verdict: '', pendingText: '判题中' } });
    expect(w.text()).toBe('判题中');
    expect(w.classes()).toContain('cj-dim');
    expect(w.find('.verdict__dot').exists()).toBe(false);
  });

  it('未出结论且未给占位文案时降级为破折号', () => {
    const w = mount(VerdictTag, { props: {} });
    expect(w.text()).toBe('—');
    expect(w.classes()).toContain('cj-dim');
  });

  it('未知结论不着色为已知结论的颜色', () => {
    const w = mount(VerdictTag, { props: { verdict: 'ZZZ' } });
    expect(w.text()).toBe('ZZZ');
    expect(w.classes()).toContain('cj-dim');
    expect(w.classes()).not.toContain('v-ac');
  });

  it('verdict 变化时响应式更新（WS 推送终态后要立刻变色）', async () => {
    const w = mount(VerdictTag, { props: { verdict: '', pendingText: '判题中' } });
    expect(w.text()).toBe('判题中');

    await w.setProps({ verdict: 'TLE' });
    expect(w.text()).toBe('超时');
    expect(w.classes()).toContain('v-tle');
  });
});
