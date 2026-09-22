import { describe, it, expect } from 'vitest';
import { API_BASE, WS_BASE, apiUrl, wsUrl } from '@/api/base';

/**
 * 请求基址拼接测试。
 *
 * `wsUrl` 里那个 `?` / `&` 的判断是**真实修过一次缺陷**的地方：
 *   `/ws/contests/1/rank?full=true` 若直接拼 `?token=`，会得到
 *   `...?full=true?token=xxx`，服务端把整串当 full 的值，
 *   表现为"内部视图订阅静默退化成公开榜"（不报错，只是数据不对）。
 */
describe('API_BASE / WS_BASE（默认推导）', () => {
  it('jsdom 下按页面主机名推导网关地址，端口固定 9080', () => {
    // 本仓库只有 .env.example，没有 .env，因此走推导分支
    expect(API_BASE).toBe('http://localhost:9080');
  });

  it('WS_BASE 由 API_BASE 换协议得到', () => {
    expect(WS_BASE).toBe('ws://localhost:9080');
  });
});

describe('apiUrl', () => {
  it('自动补前导斜杠，不产生双斜杠', () => {
    expect(apiUrl('problems/page')).toBe('http://localhost:9080/problems/page');
    expect(apiUrl('/problems/page')).toBe('http://localhost:9080/problems/page');
  });
});

describe('wsUrl', () => {
  it('带 token 时以 ? 续接', () => {
    expect(wsUrl('/ws/submissions/1', 'T1')).toBe('ws://localhost:9080/ws/submissions/1?token=T1');
  });

  it('path 已带查询参数时改用 & 续接（否则 full 参数会失效）', () => {
    expect(wsUrl('/ws/contests/1/rank?full=true', 'T1')).toBe(
      'ws://localhost:9080/ws/contests/1/rank?full=true&token=T1',
    );
    // 不能出现两个 '?'
    expect(wsUrl('/ws/x?a=1', 'T').split('?')).toHaveLength(2);
  });

  it('无 token 时不追加参数（匿名订阅公开榜）', () => {
    expect(wsUrl('/ws/x', '')).toBe('ws://localhost:9080/ws/x');
    expect(wsUrl('/ws/x', null)).toBe('ws://localhost:9080/ws/x');
    expect(wsUrl('/ws/x', undefined)).toBe('ws://localhost:9080/ws/x');
  });

  it('token 做 URL 编码（JWT 里含 . - _ 之外还可能被注入特殊字符）', () => {
    expect(wsUrl('/ws/x', 'a b&c')).toBe('ws://localhost:9080/ws/x?token=a%20b%26c');
  });

  it('path 缺前导斜杠时补上', () => {
    expect(wsUrl('ws/x', 'T')).toBe('ws://localhost:9080/ws/x?token=T');
  });
});
