import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import TagCell from '@/components/TagCell.vue';

/**
 * 标签单元格。
 *
 * 这个组件存在的唯一目的是**替掉三份各自实现**的标签渲染（题库列表 / 题目管理 /
 * 竞赛列表）。其中一处不截断直接换行，正是"标签把行高撑开、与相邻行对不齐"的成因。
 * 因此这里的断言重点不是"能不能渲染"，而是那几条**防止错位回归**的约束。
 */
describe('TagCell', () => {
  const tags = (n) =>
    Array.from({ length: n }, (_, i) => ({ id: i + 1, name: `标签${i + 1}` }));

  it('无标签时渲染占位符，且不产生标签容器节点', () => {
    for (const empty of [undefined, null, []]) {
      const w = mount(TagCell, { props: { tags: empty } });
      expect(w.text()).toBe('—');
      // 占位符不能带 .cj-cell-tags —— 那个类有 min-height 与 flex 布局，
      // 给占位符套上会得到一个"有高度的空盒子"，行高仍会被撑开
      expect(w.find('.cj-cell-tags').exists()).toBe(false);
    }
  });

  it('不超过上限时全部显示，不出现 +N', () => {
    const w = mount(TagCell, { props: { tags: tags(2), limit: 2 } });
    expect(w.findAll('.cj-cell-tags__tag').length).toBe(2);
    expect(w.find('.cj-cell-tags__more').exists()).toBe(false);
  });

  it('超过上限时截断为 limit 个并折成 +N', () => {
    const w = mount(TagCell, { props: { tags: tags(5), limit: 2 } });
    expect(w.findAll('.cj-cell-tags__tag').length).toBe(2);
    expect(w.find('.cj-cell-tags__more').text()).toBe('+3');
  });

  it('+N 的 title 给出被折掉的完整标签名（列表页没有展开交互）', () => {
    const w = mount(TagCell, { props: { tags: tags(4), limit: 2 } });
    expect(w.find('.cj-cell-tags__more').attributes('title')).toBe('标签3、标签4');
  });

  it('渲染结果的容器带 .cj-cell-tags —— 单行 nowrap 与等高由它保证', () => {
    const w = mount(TagCell, { props: { tags: tags(1) } });
    expect(w.find('.cj-cell-tags').exists()).toBe(true);
  });

  it('tags 传入非数组时退化为占位符，不抛异常（列表序号错位是比空白更糟的结果）', () => {
    for (const bad of ['abc', 0, {}]) {
      const w = mount(TagCell, { props: { tags: bad } });
      expect(w.text()).toBe('—');
    }
  });

  it('过滤掉数组里的空元素，不产生空标签', () => {
    const w = mount(TagCell, {
      props: { tags: [{ id: 1, name: '算法' }, null, undefined], limit: 3 },
    });
    expect(w.findAll('.cj-cell-tags__tag').length).toBe(1);
    expect(w.find('.cj-cell-tags__more').exists()).toBe(false);
  });

  it('limit=0 时全部折成 +N（列宽极窄时的兜底）', () => {
    const w = mount(TagCell, { props: { tags: tags(3), limit: 0 } });
    expect(w.findAll('.cj-cell-tags__tag').length).toBe(0);
    expect(w.find('.cj-cell-tags__more').text()).toBe('+3');
  });
});
