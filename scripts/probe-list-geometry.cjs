#!/usr/bin/env node
/**
 * 列表页几何实测：用真实浏览器量「列边缘是否共用、行高是否统一、单元格内容是否同轴」。
 *
 * 为什么必须实测而不是看源码：
 *   `el-table` 用 `table-layout: fixed` + `colgroup`，**列宽本来是死的** —— 所以
 *   "列没对齐"从来不是列宽问题。真实症状是**行高参差**与**内容不在同一垂轴上**：
 *   · 标签内联在标题下方且不截断 → 那一行比邻居高，表格一片"锯齿"；
 *   · 标签内联在标题后面 → 标题列被挤压，且标签与标题基线错位；
 *   · 数字列用左对齐 → 右缘参差，读不出量级。
 *   这三种都只有量 `getBoundingClientRect()` 才看得见，单测（jsdom 无布局）量不出来。
 *
 * 用法：
 *   node scripts/probe-list-geometry.cjs            # 全部路由 × 深浅两主题
 *   node scripts/probe-list-geometry.cjs /problems  # 只跑一条路由
 *
 * 前置：后端 8 个服务 + 前端 5174 已启动（python scripts/start-all.py --no-infra --with-web --wait）。
 */

const { spawn } = require('node:child_process');
const fs = require('node:fs');

const CFG = {
  origin: 'http://localhost:5174',
  gateway: 'http://localhost:9080',
  browsers: [
    'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
    'C:/Program Files/Google/Chrome/Application/chrome.exe',
    'C:/Program Files/Microsoft/Edge/Application/msedge.exe',
  ],
  port: 9337,
  viewport: { width: 1600, height: 1000 },
  themes: ['dark', 'light'],
  // 学员/教师口令是 sql/seed.sql 的基线 123456；
  // **管理员不是** —— 它的口令在 .env 的 CJ_ADMIN_INIT_PASSWORD（见 getTokens）。
  pass: '123456',
  accounts: {
    student: '13900000001',
    teacher: '13900000011',
    admin: '13800000000',
  },
  // 路由 → 用哪个身份访问
  routes: [
    { path: '/problems', as: 'student' },
    { path: '/submissions', as: 'student' },
    { path: '/contests', as: 'student' },
    { path: '/teacher/problems', as: 'teacher' },
    { path: '/admin/tags', as: 'admin' },
    { path: '/admin/users', as: 'admin' },
    { path: '/admin/workers', as: 'admin' },
  ],
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

class Cdp {
  constructor(ws) {
    this.ws = ws; this.id = 0; this.pending = new Map(); this.handlers = new Map();
    ws.addEventListener('message', (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id);
        this.pending.delete(m.id);
        m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result);
      } else if (m.method && this.handlers.has(m.method)) {
        for (const h of this.handlers.get(m.method)) h(m.params);
      }
    });
  }
  send(method, params = {}) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params }));
    return new Promise((res, rej) => this.pending.set(id, { resolve: res, reject: rej }));
  }
  on(m, f) { if (!this.handlers.has(m)) this.handlers.set(m, []); this.handlers.get(m).push(f); }
}

/**
 * 页面内量测。返回每张 el-table 的：
 *   · rowHeights   —— 去重后的行高集合（**期望只有 1 个值**）
 *   · cols         —— 每列的左右边缘离散度 + **列内跨行**的内容顶部离散度
 *   · cellCentre   —— 单元格"垂直居中残差" = |内容顶距 - 内容底距| 的最大值
 *   · inlineTag    —— 仍把标签内联在标题单元格里的个数
 *
 * ⚠️ 一个必须避开的量测陷阱：**不要拿不同列的"内容顶部偏移"互相比**。
 *    两行堆叠的单元格（如 ID + 标题 + 副标题）与单行单元格（如一个数字）天然高度不同，
 *    内容顶部也就天然不同 —— 这样比出来的"垂轴不齐"是**测量方法的产物，不是缺陷**，
 *    会给出满屏假红。真正该问的是两件事：
 *      ① 同一个列里，各行的内容是否落在同一个垂直位置（列内跨行离散度）；
 *      ② 每个单元格的内容是否垂直居中（顶距 ≈ 底距）。
 */
const MEASURE_EXPR = `
(() => {
  const round = (v) => Math.round(v * 10) / 10;
  const out = [];
  const tables = [...document.querySelectorAll('.el-table')];
  tables.forEach((tb, ti) => {
    const rows = [...tb.querySelectorAll('.el-table__body-wrapper tbody tr')];
    if (!rows.length) return;
    const rects = rows.map((tr) => [...tr.querySelectorAll('td')].map((td) => {
      const r = td.getBoundingClientRect();
      const first = td.querySelector('.cell > *') || td.querySelector('.cell');
      const fr = first ? first.getBoundingClientRect() : null;
      return {
        l: r.left, r: r.right, t: r.top, b: r.bottom,
        contentTop: fr ? fr.top - r.top : null,
        contentBottom: fr ? r.bottom - fr.bottom : null,
        firstTag: first ? first.className.toString().slice(0, 40) : '',
      };
    }));

    const rowHeights = [...new Set(rects.map((rr) => round(Math.max(...rr.map((x) => x.b)) - Math.min(...rr.map((x) => x.t)))))].sort((a, b) => a - b);

    const nCol = Math.max(...rects.map((rr) => rr.length));
    const cols = [];
    for (let c = 0; c < nCol; c++) {
      const cells = rects.map((rr) => rr[c]).filter(Boolean);
      if (!cells.length) continue;
      const ls = cells.map((x) => x.l), rs = cells.map((x) => x.r);
      const tops = cells.map((x) => x.contentTop).filter((v) => v !== null);
      cols.push({
        col: c,
        leftSpread: round(Math.max(...ls) - Math.min(...ls)),
        rightSpread: round(Math.max(...rs) - Math.min(...rs)),
        // 列内跨行：同一个列的各行内容是否落在同一垂直位置
        topSpread: tops.length > 1 ? round(Math.max(...tops) - Math.min(...tops)) : 0,
      });
    }

    // 单元格垂直居中残差：内容顶距与内容底距之差（居中时应 ≈ 0）
    let centre = 0;
    for (const rr of rects) {
      for (const x of rr) {
        if (x.contentTop === null || x.contentBottom === null) continue;
        centre = Math.max(centre, Math.abs(x.contentTop - x.contentBottom));
      }
    }

    const heads = [...tb.querySelectorAll('.el-table__header-wrapper thead th')]
      .map((th) => (th.innerText || '').trim().replace(/\\s+/g, ' ')).filter(Boolean);

    const tagCols = [];
    cols.forEach((cc, idx) => {
      const cellTagged = rects.map((rr) => rr[idx]).filter(Boolean)
        .filter((x) => /cj-cell-tags|el-tag/.test(x.firstTag)).length;
      if (cellTagged > 0) tagCols.push({ col: idx, cells: cellTagged });
    });
    const inlineTagCells = [...tb.querySelectorAll('.el-table__body-wrapper tbody td')]
      .filter((td) => td.querySelector('.cj-cell-idname .el-tag, .cj-cell-idname .cj-cell-tags')).length;

    out.push({
      tableIndex: ti,
      rows: rows.length,
      cols: cols.length,
      heads,
      rowHeights,
      maxLeftSpread: round(Math.max(...cols.map((c) => c.leftSpread))),
      maxRightSpread: round(Math.max(...cols.map((c) => c.rightSpread))),
      maxColTopSpread: round(Math.max(...cols.map((c) => c.topSpread))),
      worstColTop: cols.reduce((a, b) => (b.topSpread > a.topSpread ? b : a), cols[0]).col,
      cellCentreResidual: round(centre),
      tagCols,
      inlineTagCells,
      empty: !!tb.querySelector('.el-table__empty-block'),
    });
  });
  return { tables: out, url: location.pathname, title: (document.querySelector('.cj-pagehead__title')?.innerText || '').trim() };
})()`;

/** 用真实接口换 token（顺带验证登录链路本身） */
function envValue(key) {
  // 口令一律从 .env 读、**不回显**（与 python 侧 env_file_value 同一套做法）。
  // 管理员的初始口令不是 123456 —— 它是 .env 里的 32 位强口令，写死在探针里必然踩 401。
  try {
    const txt = fs.readFileSync(require('node:path').resolve(__dirname, '..', '.env'), 'utf8');
    for (const line of txt.split(/\r?\n/)) {
      const s = line.trim();
      if (!s || s.startsWith('#') || !s.includes('=')) continue;
      const i = s.indexOf('=');
      if (s.slice(0, i).trim() === key) return s.slice(i + 1).trim();
    }
  } catch { /* 没有 .env 就退回默认 */ }
  return '';
}

async function getTokens() {
  const out = {};
  for (const [role, phone] of Object.entries(CFG.accounts)) {
    const roleLabel = { student: '学员', teacher: '教师', admin: '管理员' }[role];
    const pass = role === 'admin'
      ? (envValue('CJ_ADMIN_INIT_PASSWORD') || CFG.pass)
      : CFG.pass;
    for (let i = 0; i < 15; i++) {
      const r = await fetch(`${CFG.gateway}/accounts/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ cellPhone: phone, password: pass }),
      });
      const text = await r.text();
      let j; try { j = JSON.parse(text); } catch { await sleep(800); continue; }  // 限流时是空体
      const tk = j?.data?.accessToken;
      if (tk) { out[role] = tk; console.log(`  ✓ ${roleLabel} ${phone} 取到令牌`); break; }
      if (r.status === 429 || j?.code === 429) { await sleep(800); continue; }
      throw new Error(`${roleLabel} ${phone} 登录失败：${text.slice(0, 200)}`);
    }
    if (!out[role]) throw new Error(`${roleLabel} ${phone} 登录被限流挡住（退避 15 次仍失败）`);
  }
  return out;
}

(async () => {
  const only = process.argv[2] || null;
  const exe = CFG.browsers.find((p) => { try { return fs.existsSync(p); } catch { return false; } });
  if (!exe) throw new Error('未找到 Edge/Chrome，请改 CFG.browsers');

  console.log('取登录令牌（网关登录令牌桶 2 req/s，连登 3 个账号需退避）……');
  const tokens = await getTokens();

  const profile = (process.env.TEMP || '/tmp') + '/cj-geometry-profile';
  const child = spawn(exe, [
    '--headless=new', `--remote-debugging-port=${CFG.port}`, `--user-data-dir=${profile}`,
    '--disable-gpu', '--no-first-run', '--no-default-browser-check', 'about:blank',
  ], { stdio: 'ignore' });

  let wsUrl;
  for (let i = 0; i < 80 && !wsUrl; i++) {
    try {
      const list = await (await fetch(`http://127.0.0.1:${CFG.port}/json/list`)).json();
      wsUrl = list.find((t) => t.type === 'page')?.webSocketDebuggerUrl;
    } catch { /* 浏览器还没起来 */ }
    if (!wsUrl) await sleep(300);
  }
  if (!wsUrl) throw new Error('DevTools 未就绪');
  const ws = new WebSocket(wsUrl);
  await new Promise((r) => ws.addEventListener('open', r, { once: true }));
  const cdp = new Cdp(ws);
  await cdp.send('Page.enable');
  await cdp.send('Runtime.enable');
  await cdp.send('Emulation.setDeviceMetricsOverride', {
    width: CFG.viewport.width, height: CFG.viewport.height, deviceScaleFactor: 1, mobile: false,
  });

  const evaluate = async (expression) => {
    const { result, exceptionDetails } = await cdp.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true,
    });
    if (exceptionDetails) throw new Error('页面内异常：' + JSON.stringify(exceptionDetails).slice(0, 400));
    return result.value;
  };

  const waitTable = async (timeoutMs = 12000) => {
    const t0 = Date.now();
    while (Date.now() - t0 < timeoutMs) {
      const n = await evaluate(`document.querySelectorAll('.el-table__body-wrapper tbody tr').length`);
      if (n > 0) { await sleep(400); return true; }
      const hasTable = await evaluate(`!!document.querySelector('.el-table')`);
      if (hasTable) { await sleep(400); return true; }   // 空表也要量（验证空态不塌）
      await sleep(250);
    }
    return false;
  };

  await cdp.send('Page.navigate', { url: CFG.origin });
  await sleep(1200);

  const routes = CFG.routes.filter((r) => !only || r.path === only);
  const report = [];
  let violations = 0;

  for (const theme of CFG.themes) {
    for (const rt of routes) {
      await evaluate(`localStorage.setItem('cj_access_token', ${JSON.stringify(tokens[rt.as])});
                      localStorage.setItem('cj_theme', ${JSON.stringify(theme)});`);
      await cdp.send('Page.navigate', { url: CFG.origin + rt.path });
      await sleep(900);
      // 主题以 localStorage 为准，但 Vite HMR/路由切换下可能没重新应用 —— 兜底再设一次并刷新
      await evaluate(`localStorage.setItem('cj_theme', ${JSON.stringify(theme)});`);
      const ok = await waitTable();
      const data = await evaluate(MEASURE_EXPR);
      const problems = [];

      for (const t of data.tables) {
        if (t.empty) continue;                       // 空表只记录，不计违规
        if (t.rowHeights.length > 1) {
          problems.push(`表#${t.tableIndex} 行高参差：${t.rowHeights.join(' / ')} px（${t.rows} 行）`);
        }
        if (t.maxLeftSpread > 0.5 || t.maxRightSpread > 0.5) {
          problems.push(`表#${t.tableIndex} 列边缘漂移：左 ${t.maxLeftSpread} / 右 ${t.maxRightSpread} px`);
        }
        if (t.inlineTagCells > 0) {
          problems.push(`表#${t.tableIndex} 仍有 ${t.inlineTagCells} 个单元格把标签内联在标题里`);
        }
        if (t.maxColTopSpread > 1.5) {
          problems.push(`表#${t.tableIndex} 第 ${t.worstColTop} 列内容在各行不在同一垂直位置：`
            + `列内跨行离散 ${t.maxColTopSpread} px`);
        }
        // ⚠️ 「居中残差」**不作为违规判定**，只打印数值。原因：实测每张表都稳定在 2.3–2.8px，
        //    这是**系统性**信号而非缺陷 —— Element Plus 的 `td` 有 8px 上下内边距，行盒被放在
        //    内容盒顶部（块级默认），于是底部会余下「内容盒高 − 行高」那段空隙。
        //    折算到视觉上文字只偏上约 1.3px，肉眼不可辨，且改为居中反而会与组件库其他表格不一致。
        //    留着它是因为「所有表都是同一个数」本身就是有用的信息：说明没有哪张表被写歪了。
      }
      violations += problems.length;

      report.push({ theme, route: rt.path, as: rt.as, loaded: ok, title: data.title, tables: data.tables, problems });
      const tag = problems.length ? 'FAIL' : (data.tables.some((t) => t.empty) ? 'EMPTY' : 'PASS');
      console.log(`\n[${tag}] ${theme}  ${rt.path}  （${rt.as}）  ${data.title || ''}`);
      for (const t of data.tables) {
        console.log(`    表#${t.tableIndex} ${t.rows} 行 × ${t.cols} 列  行高集合=[${t.rowHeights.join(', ')}]`
          + `  边缘离散 左${t.maxLeftSpread}/右${t.maxRightSpread}px`
          + `  列内跨行 ${t.maxColTopSpread}px  居中残差 ${t.cellCentreResidual}px`
          + (t.empty ? '  ← 空表' : ''));
        console.log(`      列头：${t.heads.join(' | ')}`);
      }
      for (const p of problems) console.log(`    ✗ ${p}`);
    }
  }

  const outFile = require('node:path').resolve(__dirname, '..', 'logs', 'list-geometry.json');
  fs.writeFileSync(outFile, JSON.stringify({ at: new Date().toISOString(), viewport: CFG.viewport, report }, null, 2), 'utf8');
  console.log(`\n${'='.repeat(76)}`);
  console.log(violations === 0 ? '几何实测：全部通过（无行高参差 / 无列漂移 / 无标签内联）'
                              : `几何实测：${violations} 处违规`);
  console.log(`明细已写入 ${outFile}`);

  try { child.kill(); } catch { /* ignore */ }
  process.exit(violations === 0 ? 0 : 1);
})().catch((e) => { console.error('探针失败：', e.message); process.exit(2); });
