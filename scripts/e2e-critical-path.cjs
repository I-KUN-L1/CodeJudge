#!/usr/bin/env node
/**
 * B4 前端关键路径真实浏览器 E2E（放行单 §三 B4）。
 *
 * 覆盖六段关键路径 + 安全观测，全部走真实浏览器（Edge headless via 原生 CDP，零 npm 依赖）：
 *   ① 登录（UI 填表单 → 跳转 /problems）
 *   ② 选题（题目列表 → 题目详情渲染）
 *   ③ 提交（编辑器填码 → 提交判题）
 *   ④ 判题进度 WS（/ws/submissions/{id} 收帧 + 终态落卡）
 *   ⑤ AI 点评 SSE（生成按钮 → 流式文本增长）
 *   ⑥ 竞赛榜单 WS（/ws/contests/{id}/rank 收帧 + 榜单渲染）
 *   + 观测：token 存储位置、console error 清单
 *
 * 用法：node scripts/e2e-critical-path.cjs
 * 退出码：全部 PASS=0，任一 FAIL=1（可挂 CI）。
 */

const { spawn } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const CFG = {
  origin: 'http://localhost:5174',
  gateway: 'http://127.0.0.1:9080',
  cdpPort: 9350,
  browsers: [
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  ],
  phone: '13900000001',
  pass: '123456',
  judgeTimeoutMs: 150000,
  outDir: path.resolve(__dirname, '..', 'logs', 'e2e'),
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/* ---------------- CDP 最小封装（事件 + 请求双通道） ---------------- */
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

/* ---------------- 结果收集 ---------------- */
const results = [];
const record = (name, pass, detail) => {
  results.push({ name, pass, detail });
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}  ${detail || ''}`);
};
const consoleErrors = [];

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

  /* ---------- 前置：API 层确认种子数据 ---------- */
  const loginApi = async () => {
    const resp = await fetch(`${CFG.gateway}/accounts/login`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cellPhone: CFG.phone, password: CFG.pass }),
    });
    const body = await resp.json();
    if (body.code !== 200 || !body.data?.accessToken) throw new Error('学员登录失败：' + JSON.stringify(body).slice(0, 200));
    return body.data.accessToken;
  };
  const token = await loginApi();
  const authed = { Authorization: 'Bearer ' + token };
  const problems = (await (await fetch(`${CFG.gateway}/problems/page?pageNo=1&pageSize=5`, { headers: authed })).json())?.data?.list || [];
  if (!problems.length) throw new Error('题目列表为空，种子数据缺失');
  const problem = problems.find((p) => p.status === 1) || problems[0];
  const contests = (await (await fetch(`${CFG.gateway}/contests/page?pageNo=1&pageSize=10`, { headers: authed })).json())?.data?.list || [];
  const contest = contests.find((c) => c.status === 1) || contests[0];
  console.log(`前置就绪：题目 ${problem.id}《${problem.title}》；竞赛 ${contest ? contest.id : '无（⑥将 FAIL）'}`);

  /* ---------- 浏览器 ---------- */
  const exe = CFG.browsers.find((p) => fs.existsSync(p));
  if (!exe) throw new Error('未找到 Edge：' + CFG.browsers.join(' / '));
  const profile = (process.env.TEMP || '/tmp') + '/cj-e2e-profile';
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
  await cdp.send('Network.enable');
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

  /* WS 帧收集：按路径分流 */
  const wsUrls = new Map();
  const progressFrames = []; const rankFrames = []; const otherWs = [];
  cdp.on('Network.webSocketCreated', (p) => wsUrls.set(p.requestId, p.url));
  cdp.on('Network.webSocketFrameReceived', (p) => {
    const url = wsUrls.get(p.requestId) || '';
    const frame = { url: url.replace(/^ws:\/\/[^/]+/, ''), payload: (p.response?.payloadData || '').slice(0, 400) };
    if (url.includes('/ws/submissions')) progressFrames.push(frame);
    else if (url.includes('/ws/contests')) rankFrames.push(frame);
    else if (url.includes('/ws/')) otherWs.push(frame);
  });
  cdp.on('Runtime.consoleAPICalled', (p) => {
    if (p.type === 'error') consoleErrors.push(p.args?.map((a) => a.value || a.description).join(' ').slice(0, 200));
  });
  cdp.on('Runtime.exceptionThrown', (p) => {
    consoleErrors.push(String(p.exceptionDetails?.exception?.description || p.exceptionDetails?.text || '').slice(0, 200));
  });

  const PROBE = (extra) => `(() => {
    const txt = (sel) => (document.querySelector(sel)?.innerText || '').trim();
    return Object.assign({
      url: location.pathname + location.search,
      title: txt('.cj-title') || document.title,
      messages: [...document.querySelectorAll('.el-message')].map((m) => m.innerText.trim()),
      alerts: [...document.querySelectorAll('.el-alert__title')].map((a) => a.innerText.trim()),
      tables: [...document.querySelectorAll('.el-table')].map((t) => t.querySelectorAll('.el-table__body-wrapper tbody tr').length),
    }, ${extra || '{}'});
  })()`;
  const clickButtonByText = (text) => `(() => {
    const btn = [...document.querySelectorAll('button')].find((b) => b.innerText.trim() === ${JSON.stringify(text)} && !b.disabled);
    if (!btn) return false;
    btn.click(); return true;
  })()`;

  try {
    /* ---------- ① 登录 ---------- */
    await goto(CFG.origin + '/login');
    // profile 复用时上次会话仍有效，/login 会被路由守卫重定向 —— 清掉本地态强制走真实登录
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
      set(document.querySelector('input[placeholder="11 位手机号"]'), ${JSON.stringify(CFG.phone)});
      set(document.querySelector('input[placeholder="请输入密码"]'), ${JSON.stringify(CFG.pass)});
    })()`);
    await evaluate(clickButtonByText('登录'));
    await waitUntil(async () => {
      const p = await evaluate('location.pathname');
      return p === '/problems' || p === '/';
    }, 10000, '登录跳转');
    const afterLogin = await evaluate(PROBE());
    record('① UI 登录', afterLogin.url === '/problems', 'url=' + afterLogin.url);
    await shoot('01-after-login');

    /* ---------- ② 选题 ---------- */
    await goto(`${CFG.origin}/problems/${problem.id}`);
    await waitUntil(async () => {
      const p = await evaluate(PROBE(`{ detail: !!document.querySelector('.editor__ta') || !!document.querySelector('.el-descriptions') }`));
      return p.detail === true;
    }, 15000, '题目详情渲染');
    const problemPage = await evaluate(PROBE());
    record('② 题目详情渲染', !problemPage.alerts.length && problemPage.title && problemPage.title !== 'CodeJudge',
      `title=${(problemPage.title || '').slice(0, 30)} alerts=${JSON.stringify(problemPage.alerts)}`);
    await shoot('02-problem-detail');

    /* ---------- ③ 提交 ---------- */
    const codeBefore = await evaluate(`document.querySelector('.editor__ta')?.value || ''`);
    const sample = codeBefore.trim().length >= 10 ? null
      : '#include <bits/stdc++.h>\nusing namespace std;\nint main(){int a,b;cin>>a>>b;cout<<a+b<<endl;}';
    if (sample) {
      await evaluate(`(() => {
        const ta = document.querySelector('.editor__ta');
        ta.focus();
        document.execCommand('selectAll', false, null);
        document.execCommand('insertText', false, ${JSON.stringify(sample)});
      })()`);
    }
    const clicked = await evaluate(clickButtonByText('提交判题'));
    if (!clicked) throw new Error('提交按钮不可点');
    let submissionId = null;
    await waitUntil(async () => {
      const hit = progressFrames.find((f) => /\/ws\/submissions\/(\d+)/.test(f.url));
      if (hit) submissionId = hit.url.match(/\/ws\/submissions\/(\d+)/)[1];
      return !!submissionId;
    }, 15000, '判题进度 WS 建立');
    record('③ 提交判题', !!submissionId, `submissionId=${submissionId} 预填模板=${sample ? '否' : '是'}`);
    await shoot('03-submitted');

    /* ---------- ④ 判题进度 WS + 终态 ---------- */
    const progressCardText = async () => evaluate(`(() => {
      const cards = [...document.querySelectorAll('.cj-card')];
      const card = cards.find((c) => c.innerText.includes('判题进度'));
      return card ? card.innerText.replace(/\\s+/g, ' ').slice(0, 160) : '';
    })()`);
    let finalVerdict = '';
    try {
      await waitUntil(async () => {
        const t = await progressCardText();
        if (/(Accepted|AC|Wrong Answer|WA|TLE|Runtime Error|RE|Compile Error|CE|判题完成|评测完成)/i.test(t)) {
          finalVerdict = t; return true;
        }
        return progressFrames.some((f) => /SNAPSHOT|verdict/i.test(f.payload) && /AC|WA|TLE|RE|CE|Accepted/i.test(f.payload))
          && (finalVerdict = 'ws:' + progressFrames[progressFrames.length - 1].payload.slice(0, 80));
      }, CFG.judgeTimeoutMs, '判题终态');
      record('④ 判题进度 WS + 终态', true, `frames=${progressFrames.length} verdict=${finalVerdict.slice(0, 60)}`);
    } catch (e) {
      record('④ 判题进度 WS + 终态', false, `frames=${progressFrames.length} lastCard=${(await progressCardText()).slice(0, 80)}`);
    }
    await shoot('04-verdict');

    /* ---------- ⑤ AI 点评 SSE ---------- */
    await goto(`${CFG.origin}/submissions/${submissionId}`);
    await waitUntil(async () => evaluate(`!![...document.querySelectorAll('button')].find((b) => b.innerText.includes('生成 AI 点评'))`), 15000, 'AI 点评面板');
    const grow = [];
    const contentText = () => evaluate(`(() => {
      const panel = [...document.querySelectorAll('.cj-card, .el-card')].find((c) => c.innerText.includes('AI 点评'));
      return panel ? panel.innerText.length : 0;
    })()`);
    await evaluate(clickButtonByText('生成 AI 点评'));
    for (let i = 0; i < 4; i++) { grow.push(await contentText()); await sleep(2000); }
    const degradedTag = await evaluate(`[...document.querySelectorAll('.el-tag')].some((t) => t.innerText.includes('降级'))`);
    const sseOk = grow[3] > grow[0] && grow[3] > 50;
    record('⑤ AI 点评 SSE 流式', sseOk, `len=${grow.join('→')} 降级=${degradedTag}`);
    await shoot('05-ai-review');

    /* ---------- ⑥ 竞赛榜单 WS ---------- */
    if (contests.length) {
      // 种子竞赛多为历史窗口（未开始/已结束）：实时 WS 只在比赛窗口内推送，
      // 已结束的走终榜快照（REST）。逐个尝试，区分两种证据：
      //   live = 收到 /ws/contests 帧；board = 榜单表格有行（快照/终榜渲染）
      let best = null;
      for (const c of contests.slice(0, 3)) {
        const before = rankFrames.length;
        await goto(`${CFG.origin}/contests/${c.id}`);
        await sleep(3500);
        const p = await evaluate(PROBE(`{
          rankRows: [...document.querySelectorAll('.el-table')].pop()?.querySelectorAll('.el-table__body-wrapper tbody tr').length ?? null,
          wsTag: [...document.querySelectorAll('.el-tag')].map((t) => t.innerText.trim()).filter((t) => t.includes('连接'))[0] || '',
        }`));
        const live = rankFrames.length - before;
        const attempt = { id: c.id, title: c.title, live, rows: p.rankRows, wsTag: p.wsTag, alerts: p.alerts };
        if (!best || (attempt.live > best.live) || (attempt.live === best.live && (attempt.rows || 0) > (best.rows || 0))) best = attempt;
        if (attempt.live > 0 && attempt.rows > 0) break; // 实时榜完整证据，无需再试
      }
      const ok = best && (best.live > 0 || best.rows > 0) && !best.alerts.some((a) => a.includes('不存在'));
      const mode = best?.live > 0 ? '实时 WS' : best?.rows > 0 ? '终榜/快照（REST）' : '无渲染';
      record('⑥ 竞赛榜单', !!ok, `[${mode}] id=${best?.id} live帧=${best?.live} rows=${best?.rows} wsTag=${best?.wsTag} alerts=${JSON.stringify(best?.alerts)}`);
    } else {
      record('⑥ 竞赛榜单', false, '无可用竞赛（种子数据缺失）');
    }
    await shoot('06-contest-rank');

    /* ---------- + 安全/健康观测 ---------- */
    const storage = await evaluate(`({
      localKeys: Object.keys(localStorage),
      sessionKeys: Object.keys(sessionStorage),
      tokenInLocal: (localStorage.getItem('cj_access_token') || Object.values(localStorage).some((v) => typeof v === 'string' && v.split('.').length === 3)),
    })`);
    // access token 放 localStorage 是项目文档化的取舍（网关要求 Bearer）；记录不判 FAIL
    record('+ token 存储观测', true, `local=[${storage.localKeys}] tokenPresent=${storage.tokenInLocal}（localStorage 方案为文档化取舍，见 stores/user.js 注释）`);
    record('+ console error 清点', consoleErrors.length === 0,
      consoleErrors.length ? consoleErrors.slice(0, 5).join(' | ') : '0 条');
  } catch (e) {
    record('E2E 流程中断', false, e.message);
    try { await shoot('99-interrupted'); } catch { /* ignore */ }
  } finally {
    child.kill();
    ws.close();
  }

  const failed = results.filter((r) => !r.pass);
  console.log(`\n===== E2E 汇总：${results.length - failed.length}/${results.length} PASS =====`);
  process.exit(failed.length ? 1 : 0);
})().catch((e) => { console.error('E2E 致命错误：', e.message); process.exit(2); });
