import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import {
  VERDICT,
  verdictLabel,
  verdictCls,
  statusLabel,
  statusTagType,
  isTerminalStatus,
  languageLabel,
  difficultyLabel,
  difficultyTagType,
  problemStatusLabel,
  problemStatusTagType,
  contestStatusLabel,
  contestStatusTagType,
  userTypeLabel,
  userStatusLabel,
  toDate,
  fmtTime,
  fmtDate,
  fmtFromNow,
  fmtMemory,
  fmtDuration,
  fmtPercent,
  fmtPenalty,
  icpcCellClass,
} from '@/utils/format';

/**
 * 展示层格式化的口径测试。
 *
 * 这些函数看着琐碎，但每个页面都在用 —— 口径漂移的典型表现是
 * "列表页显示【答案错误】、详情页显示【WA】"，属于最容易被投诉、最难自查的一类缺陷。
 * 因此把映射表本身也纳入断言。
 */

describe('判题结论 VERDICT', () => {
  it('覆盖全部 7 种终态结论，且 label/full/cls 三者齐全', () => {
    expect(Object.keys(VERDICT).sort()).toEqual(
      ['AC', 'CE', 'MLE', 'RE', 'SE', 'TLE', 'WA'].sort(),
    );
    for (const [key, v] of Object.entries(VERDICT)) {
      expect(v.label, `${key}.label`).toBeTruthy();
      expect(v.full, `${key}.full`).toBeTruthy();
      expect(v.cls, `${key}.cls`).toBeTruthy();
    }
  });

  it('已知结论取中文 label', () => {
    expect(verdictLabel('AC')).toBe('通过');
    expect(verdictLabel('WA')).toBe('答案错误');
    expect(verdictLabel('TLE')).toBe('超时');
    expect(verdictLabel('CE')).toBe('编译错误');
    expect(verdictLabel('SE')).toBe('系统错误');
  });

  it('未知结论原样透出，空值降级为破折号（不显示 undefined）', () => {
    expect(verdictLabel('XX')).toBe('XX');
    expect(verdictLabel('')).toBe('—');
    expect(verdictLabel(null)).toBe('—');
    expect(verdictLabel(undefined)).toBe('—');
  });

  it('颜色类名：已知结论用专属类，空值统一 cj-dim', () => {
    expect(verdictCls('AC')).toBe('v-ac');
    expect(verdictCls('WA')).toBe('v-wa');
    expect(verdictCls('')).toBe('cj-dim');
    expect(verdictCls(null)).toBe('cj-dim');
    // 未知结论不得复用某个已知颜色，否则「编造出来的结论」会看起来像 AC
    expect(verdictCls('XX')).toBe('cj-dim');
  });
});

describe('提交状态 SUBMISSION_STATUS', () => {
  it('四种状态均有中文与 tag 类型', () => {
    expect(statusLabel('PENDING')).toBe('排队中');
    expect(statusLabel('JUDGING')).toBe('判题中');
    expect(statusLabel('SUCCESS')).toBe('已完成');
    expect(statusLabel('FAILED')).toBe('失败');
    expect(statusTagType('SUCCESS')).toBe('success');
    expect(statusTagType('FAILED')).toBe('danger');
    expect(statusTagType('NOPE')).toBe('info');
  });

  it('终态判定只含 SUCCESS/FAILED —— 这是停止 WS 订阅与轮询的唯一依据', () => {
    expect(isTerminalStatus('SUCCESS')).toBe(true);
    expect(isTerminalStatus('FAILED')).toBe(true);
    expect(isTerminalStatus('PENDING')).toBe(false);
    expect(isTerminalStatus('JUDGING')).toBe(false);
    expect(isTerminalStatus('')).toBe(false);
    expect(isTerminalStatus(null)).toBe(false);
  });
});

describe('语言 / 难度 / 业务状态', () => {
  it('语言名与后端 Language 枚举一致的四门', () => {
    expect(languageLabel('JAVA')).toBe('Java 21');
    expect(languageLabel('CPP')).toBe('GCC 13 (C++17)');
    expect(languageLabel('PYTHON')).toBe('Python 3.12');
    expect(languageLabel('GO')).toBe('Go 1.22');
    expect(languageLabel('RUST')).toBe('RUST');
    expect(languageLabel(null)).toBe('—');
  });

  it('难度 1..5 有中文，0/越界归入未分级', () => {
    expect(difficultyLabel(1)).toBe('入门');
    expect(difficultyLabel(3)).toBe('中等');
    expect(difficultyLabel(5)).toBe('地狱');
    expect(difficultyLabel(0)).toBe('未分级');
    expect(difficultyLabel(9)).toBe('未分级');
    // tag 颜色是按难度递增的梯度：1 绿 → 2 蓝 → 3 橙 → 4/5 红
    expect(difficultyTagType(1)).toBe('success');
    expect(difficultyTagType(2)).toBe('primary');
    expect(difficultyTagType(3)).toBe('warning');
    expect(difficultyTagType(4)).toBe('danger');
    expect(difficultyTagType(5)).toBe('danger');
    expect(difficultyTagType(0)).toBe('info');
    expect(difficultyTagType(9)).toBe('info');
  });

  it('题目与竞赛状态按数字枚举映射', () => {
    expect(problemStatusLabel(0)).toBe('草稿');
    expect(problemStatusLabel(1)).toBe('已发布');
    expect(problemStatusLabel(2)).toBe('已下线');
    expect(problemStatusLabel(9)).toBe('—');
    expect(problemStatusTagType(1)).toBe('success');

    expect(contestStatusLabel(0)).toBe('未开始');
    expect(contestStatusLabel(1)).toBe('进行中');
    expect(contestStatusLabel(2)).toBe('已结束');
    expect(contestStatusTagType(2)).toBe('warning');
  });

  it('用户类型 / 启用状态沿用底座约定（1 正常 0 禁用）', () => {
    expect(userTypeLabel(1)).toBe('管理员');
    expect(userTypeLabel(2)).toBe('学员');
    expect(userTypeLabel(3)).toBe('教师');
    expect(userTypeLabel(99)).toBe('未知');
    expect(userStatusLabel(1)).toBe('正常');
    expect(userStatusLabel(0)).toBe('已禁用');
  });
});

describe('时间归一化 toDate —— 后端时间格式不统一，归一失败必须落到 null 而不是 Invalid Date', () => {
  it('接受 Date 实例并原样返回', () => {
    const d = new Date(2026, 0, 1);
    expect(toDate(d)).toBe(d);
  });

  it('接受 Jackson 默认的 ISO 数组 [y,M,d,h,m,s]（月份 1-based）', () => {
    const d = toDate([2026, 9, 21, 15, 30, 5]);
    expect(d).toBeInstanceOf(Date);
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(8); // 9 月 → 索引 8
    expect(d.getDate()).toBe(21);
    expect(d.getHours()).toBe(15);
    expect(d.getMinutes()).toBe(30);
    expect(d.getSeconds()).toBe(5);
  });

  it('数组缺位时按默认值补齐，不产生 NaN', () => {
    const d = toDate([2026, 9]);
    expect(d.getFullYear()).toBe(2026);
    expect(d.getMonth()).toBe(8);
    expect(d.getDate()).toBe(1);
    expect(d.getHours()).toBe(0);
  });

  it('接受 "yyyy-MM-dd HH:mm:ss"（Safari 下空格分隔会解析失败，需替换分隔符）', () => {
    const d = toDate('2026-09-21 15:30:00');
    expect(Number.isNaN(d.getTime())).toBe(false);
    expect(d.getHours()).toBe(15);
    expect(d.getMinutes()).toBe(30);
  });

  it('接受 ISO 字符串', () => {
    const d = toDate('2026-09-21T15:30:00');
    expect(Number.isNaN(d.getTime())).toBe(false);
    expect(d.getFullYear()).toBe(2026);
  });

  it('非法输入一律返回 null，绝不放行 Invalid Date 到页面', () => {
    expect(toDate('')).toBeNull();
    expect(toDate(null)).toBeNull();
    expect(toDate(undefined)).toBeNull();
    expect(toDate('不是一个时间')).toBeNull();
    expect(toDate(12345)).toBeNull();
    expect(toDate({})).toBeNull();
  });
});

describe('时间 / 数字展示', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 8, 21, 15, 30, 0));
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('fmtTime 默认带秒，可关闭', () => {
    const d = new Date(2026, 8, 21, 9, 5, 7);
    expect(fmtTime(d)).toBe('2026-09-21 09:05:07');
    expect(fmtTime(d, false)).toBe('2026-09-21 09:05');
    expect(fmtTime(null)).toBe('—');
  });

  it('fmtDate 只到日', () => {
    expect(fmtDate(new Date(2026, 8, 21, 9, 5, 7))).toBe('2026-09-21');
    expect(fmtDate('bad')).toBe('—');
  });

  it('fmtFromNow 过去 / 未来 / 一分钟内三档', () => {
    const now = Date.now();
    // ⚠ 必须传 Date（或后端实际下发的字符串/数组）——
    //   toDate() 对**数字**时间戳刻意返回 null，因为后端从不下发 epoch 毫秒；
    //   传数字得到 '—' 是契约而不是缺陷（见上面 toDate 的用例）。
    const at = (offset) => new Date(now + offset);
    expect(fmtFromNow(at(-10_000))).toBe('刚刚');
    expect(fmtFromNow(at(-5 * 60_000))).toBe('5分钟前');
    expect(fmtFromNow(at(-3 * 3_600_000))).toBe('3小时前');
    expect(fmtFromNow(at(-2 * 86_400_000))).toBe('2天前');
    expect(fmtFromNow(at(2 * 3_600_000))).toBe('2小时后');
    expect(fmtFromNow(null)).toBe('—');
    // 裸数字时间戳不被支持（契约），返回 '—' 而不是错误的时间
    expect(fmtFromNow(now - 10_000)).toBe('—');
  });

  it('fmtMemory 以 1024 为界切换单位', () => {
    expect(fmtMemory(512)).toBe('512 KB');
    expect(fmtMemory(1023)).toBe('1023 KB');
    expect(fmtMemory(1024)).toBe('1.0 MB');
    expect(fmtMemory(2048)).toBe('2.0 MB');
    expect(fmtMemory(0)).toBe('0 KB');
    expect(fmtMemory(null)).toBe('—');
    expect(fmtMemory(undefined)).toBe('—');
  });

  it('fmtDuration 以 1000ms 为界切换单位', () => {
    expect(fmtDuration(0)).toBe('0 ms');
    expect(fmtDuration(999)).toBe('999 ms');
    expect(fmtDuration(1000)).toBe('1.00 s');
    expect(fmtDuration(2345)).toBe('2.35 s');
    expect(fmtDuration(null)).toBe('—');
  });

  it('fmtPercent 保留后端"提交数为 0 时下发 null"的语义（显示 — 而不是 0%）', () => {
    expect(fmtPercent(0)).toBe('0%');
    expect(fmtPercent(66.7)).toBe('66.7%');
    expect(fmtPercent(null)).toBe('—');
    expect(fmtPercent(undefined)).toBe('—');
  });

  it('fmtPenalty：罚时秒 → mm:ss，满一小时才有 h 段', () => {
    expect(fmtPenalty(0)).toBe('0:00');
    expect(fmtPenalty(5)).toBe('0:05');
    expect(fmtPenalty(125)).toBe('2:05');
    expect(fmtPenalty(3725)).toBe('1:02:05');
    expect(fmtPenalty(null)).toBe('—');
  });

  it('icpcCellClass：+ 开头为通过色，其余非空为错误色，空为灰', () => {
    expect(icpcCellClass('+')).toBe('v-ac');
    expect(icpcCellClass('+2')).toBe('v-ac');
    expect(icpcCellClass('-3')).toBe('v-wa');
    expect(icpcCellClass('')).toBe('cj-dim');
    expect(icpcCellClass(null)).toBe('cj-dim');
  });
});
