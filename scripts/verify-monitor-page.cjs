#!/usr/bin/env node
/**
 * 系统监控页真实浏览器验证（修复「所有服务均不可达」后的回归防线）。
 *
 * 背景：监控页从浏览器**直连**各服务端口读 /actuator/health 与 /actuator/prometheus
 * （actuator 不在网关路由内）。8 个服务此前未配 actuator CORS，跨源响应被浏览器
 * 整体拦截 → 页面上 8/8 全部「不可达」，与服务是否存活无关。修复 = 各服务
 * application.yml 的 management.endpoints.web.cors（见 2026-09-27 变更）。
 *
 * 本脚本走真实浏览器（Edge headless via 原生 CDP，零 npm 依赖，与 e2e-critical-path.cjs
 * 同一套模式）：管理员 UI 登录 → /admin/monitor → 等待 probeAllServices 出结果 →
 * 断言 8/8 UP、指标抓取非零 → 截图留档。
 *
 * 用法：node scripts/verify-monitor-page.cjs
 * 退出码：全部 PASS=0，任一 FAIL=1（可挂 CI）。
 */

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const CFG = {
  origin: 'http://localhost:5174',
  gateway: 'http://127.0.0.1:9080',
  cdpPort: 9351,
  browsers: [
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  ],
  // 管理员凭据从 .env 读（CJ_ADMIN_PHONE / CJ_ADMIN_INIT_PASSWORD），不硬编码
  envFile: path.resolve(__dirname, '..', '.env'),
  outDir: path.resolve(__dirname, '..', 'logs', 'e2e'),
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function envValue(key) {
  try {
    for (const line of fs.readFileSync(CFG.envFile, 'utf8').split(/\r?\n/)) {
      const s = line.trim();
      if (!s || s.startsWith('#') || !s.includes('=')) continue;
      const [k, ...rest] = s.split('=');
      if (k.trim() === key) return rest.join('=').trim();
    }
  } catch { /* .env 缺失时由调用方报错 */ }
  return '';
}

/* ---------------- CDP 最小封装（与 e2e-critical-path.cjs 同款） ---------------- */
class Cdp {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.handlers = new Map();
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id);
        this.pending.delete(m.id);
        m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
      } else if (m.method) {
        (this.handlers.get(m.method) || []).forEach((fn) => fn(m.params));
      }
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((res, rej) => this.pending.set(id, { resolve: res, reject: rej }));
  }
  on(method, fn) {
    if (!this.handlers.has(method)) this.handlers.set(method, []);
    this.handlers.get(method).push(fn);
  }
}

const results = [];
const record = (name, pass, detail) => {
  results.push({ name, pass });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  ${detail || ''}`);
};

async function waitUntil(fn, timeoutMs, desc, intervalMs = 500) {
  const t0 = Date.now();
  for (;;) {
    let v;
    try { v = await fn(); } catch { /* DOM 未就绪 */ }
    if (v) return v;
    if (Date.now() - t0 > timeoutMs) throw new Error(`等待超时（${desc}，${timeoutMs}ms）`);
    await sleep(intervalMs);
  }
}

(async () => {
  fs.mkdirSync(CFG.outDir, { recursive: true });
  const phone = envValue('CJ_ADMIN_PHONE') || '13800000000';
  const pass = envValue('CJ_ADMIN_INIT_PASSWORD');
  if (!pass) throw new Error('.env 缺少 CJ_ADMIN_INIT_PASSWORD，无法登录管理员');

  const exe = CFG.browsers.find((p) => fs.existsSync(p));
  if (!exe) throw new Error('未找到 Edge：' + CFG.browsers.join(' / '));
  const profile = (process.env.TEMP || '/tmp') + '/cj-monitor-profile';
  const child = spawn(exe, [
    '--headless=new', `--remote-debugging-port=${CFG.cdpPort}`, `--user-data-dir=${profile}`,
    '--disable-gpu', '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: 'ignore' });
  let wsUrl;
  for (let i = 0; i < 80 && !wsUrl; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${CFG.cdpPort}/json/list`)).json();
      wsUrl = list.find((t) => t.type === 'page')?.webSocketDebuggerUrl;
    } catch { /* 启动中 */ }
    if (!wsUrl) await sleep(300);
  }
  const ws = new WebSocket(wsUrl);
  await new Promise((r) => ws.addEventListener('open', r, { once: true }));
  const cdp = new Cdp(ws);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', { width: 1600, height: 1020, deviceScaleFactor: 1, mobile: false });

  const evaluate = async (expression) => {
    const { result, exceptionDetails } = await cdp.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
    if (exceptionDetails) throw new Error(JSON.stringify(exceptionDetails).slice(0, 300));
    return result.value;
  };
  const shoot = async (name) => {
    const { data } = await cdp.send('Page.captureScreenshot', { format: 'png' });
    fs.writeFileSync(path.join(CFG.outDir, name + '.png'), Buffer.from(data, 'base64'));
  };
  const goto = async (url) => { await cdp.send('Page.navigate', { url }); await sleep(1800); };
  const clickButtonByText = (text) => `(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.innerText.trim() === ${JSON.stringify(text)} && !b.disabled);
    if (!btn) return false;
    btn.click(); return true;
  })()`;

  try {
    /* ---------- 管理员 UI 登录 ---------- */
    await goto(CFG.origin + '/login');
    if (!(await evaluate(`!!document.querySelector('input[placeholder="11 位手机号"]')`))) {
      await evaluate(`localStorage.clear(); sessionStorage.clear();`);
      await goto(CFG.origin + '/login');
    }
    const atLogin = await evaluate(`!!document.querySelector('input[placeholder="11 位手机号"]')`);
    if (!atLogin) throw new Error('登录表单未渲染');
    await evaluate(`(() => {
      const set = (el, v) => {
        const proto = el.tagName === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
        Object.getOwnPropertyDescriptor(proto, 'value').set.call(el, v);
        el.dispatchEvent(new Event('input', { bubbles: true }));
      };
      set(document.querySelector('input[placeholder="11 位手机号"]'), ${JSON.stringify(phone)});
      set(document.querySelector('input[placeholder="请输入密码"]'), ${JSON.stringify(pass)});
    })()`);
    await evaluate(clickButtonByText('登录'));
    await waitUntil(async () => {
      const p = await evaluate('location.pathname');
      return p !== '/login';
    }, 10000, '管理员登录跳转');
    record('① 管理员 UI 登录', true, `落地 ${await evaluate('location.pathname')}`);

    /* ---------- 打开监控页 ---------- */
    await goto(CFG.origin + '/admin/monitor');
    // probeAllServices 完成 = 服务方块渲染出来（.svc 有 8 个）
    await waitUntil(async () => {
      const n = await evaluate(`document.querySelectorAll('.svc').length`);
      return n >= 8;
    }, 20000, '服务健康卡片渲染（8 个）');

    // 指标抓取（/actuator/prometheus）也等一下：页面 metricsLoaded 出现在「指标总览」头
    await waitUntil(async () => {
      const t = await evaluate(`(() => {
        const heads = [...document.querySelectorAll('.cj-card__head')].map((h) => h.innerText);
        return heads.find((s) => s.includes('指标总览')) || '';
      })()`);
      return /\d+ \/ \d+ 个服务/.test(t || '');
    }, 20000, '指标抓取完成');

    const state = await evaluate(`(() => {
      const cards = [...document.querySelectorAll('.svc')].map((c) => ({
        name: c.querySelector('.svc__name')?.innerText.trim(),
        up: !c.classList.contains('is-down'),
        meta: c.innerText.replace(/\\s+/g, ' ').slice(0, 80),
      }));
      const heads = [...document.querySelectorAll('.cj-card__head')].map((h) => h.innerText.replace(/\\s+/g, ' '));
      return {
        cards,
        upLine: heads.find((s) => s.includes('UP')) || '',
        metricsLine: heads.find((s) => s.includes('指标总览')) || '',
        metricsError: document.querySelector('.el-alert--warning .el-alert__title, .el-alert--error .el-alert__title')?.innerText.trim() || '',
      };
    })()`);

    record('② 服务健康 8/8 UP', state.cards.length === 8 && state.cards.every((c) => c.up),
      state.cards.map((c) => `${c.name}:${c.up ? 'UP' : 'DOWN'}`).join(' '));
    record('③ 无「不可达」方块', state.cards.every((c) => !c.meta.includes('不可达')),
      `upLine=${state.upLine}`);
    const m = (state.metricsLine.match(/(\d+) \/ (\d+) 个服务/) || []);
    record('④ Prometheus 指标抓取非零', Number(m[1]) > 0, state.metricsLine);
    record('⑤ 无全局错误告警条', !state.metricsError, state.metricsError || '（无）');

    await shoot('monitor-page-fixed');
    console.log(`截图：${path.join(CFG.outDir, 'monitor-page-fixed.png')}`);
  } finally {
    child.kill();
  }

  const fail = results.filter((r) => !r.pass).length;
  console.log(`\n校验结果：PASS=${results.length - fail} FAIL=${fail}`);
  process.exit(fail ? 1 : 0);
})().catch((e) => {
  console.error('脚本异常：', e.message);
  process.exit(1);
});
