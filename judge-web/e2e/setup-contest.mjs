// E2E 前置脚本（U4b）：创建一场「进行中」的演示赛并让种子学员报名，
// 供榜单联动链路（提交→判题→实时榜）使用。
//
// 为什么需要它：种子/历史竞赛到跑 E2E 时多半已结束，榜单一律为空；
// 而reset-demo-data.py 是破坏性清洗，E2E 不应依赖它。本脚本**只新增**数据
// （一场新竞赛 + 一次报名），不触碰既有提交/榜单，可重复执行（每次生成新 id）。
//
// 用法（在 judge-web/ 目录内）：
//   node e2e/setup-contest.mjs
// 输出：
//   E2E_CONTEST_ID=<id>   —— 传给 playwright（npm 脚本里 set / $env: 均可）
//
// 登录限流预算：本脚本 2 次登录（教师 + 学员），浏览器链路 1 次，
// 全程共 3 次 << 限流突发 5，安全。

const GW = process.env.E2E_GATEWAY || 'http://127.0.0.1:9080';

const TEACHER_PHONE = '13900000011'; // 演示教师一（seed.sql）
const STUDENT_PHONE = '13900000001'; // 演示学员一（E2E 主角，同 smoke 链路）
const PASSWORD = '123456';
const PROBLEM_ID = 4001; // A + B Problem（题库种子，样例含 int 溢出用例）

/**
 * ⚠️ 时间基准必须用 **UTC 墙钟**（不带 Z 的 ISO 本地时间字段）：
 * compose 只给 mysql/pg 配了 TZ=Asia/Shanghai，8 个 Java 服务容器默认 UTC，
 * judge-contest 用 LocalDateTime.now()（UTC）与窗口比较。若按宿主 CST 发时间，
 * 竞赛会被判"尚未开始"整整 8 小时（详见执行报告第十轮 TZ 时钟缺陷）。
 * 这里与服务端时钟对齐是测试基建的对齐，不是对缺陷的掩盖。
 */
function fmtUtcLocal(d) {
  const p = (n) => String(n).padStart(2, '0');
  return (
    `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}` +
    `T${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`
  );
}

async function login(phone, password) {
  const r = await fetch(`${GW}/accounts/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cellPhone: phone, password }),
  }).then((x) => x.json());
  if (r.code !== 200 || !r.data?.accessToken) {
    throw new Error(`登录失败 ${phone}: ${JSON.stringify(r).slice(0, 200)}`);
  }
  return r.data.accessToken;
}

const teacher = await login(TEACHER_PHONE, PASSWORD);
const student = await login(STUDENT_PHONE, PASSWORD);

const now = new Date();
const stamp = fmtUtcLocal(now).slice(0, 16).replace('T', ' ');
const body = {
  title: `【E2E】榜单联动赛 ${stamp}`,
  description:
    'Playwright E2E 专用演示赛（进行中）：用于「选题→提交→判题→实时榜联动」链路断言，可随时删除。',
  rule: 'ACM',
  startTime: fmtUtcLocal(new Date(now.getTime() - 60_000)),
  endTime: fmtUtcLocal(new Date(now.getTime() + 120 * 60_000)),
  freezeMinutes: 30,
  penaltyMinutes: 20,
  problems: [{ problemId: PROBLEM_ID, label: 'A', displayOrder: 0, fullScore: 100 }],
};

const created = await fetch(`${GW}/contests`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${teacher}` },
  body: JSON.stringify(body),
}).then((x) => x.json());
if (created.code !== 200 || !created.data?.id) {
  throw new Error(`创建竞赛失败: ${JSON.stringify(created).slice(0, 300)}`);
}
const contestId = String(created.data.id);

const reg = await fetch(`${GW}/contests/${contestId}/register`, {
  method: 'POST',
  headers: { Authorization: `Bearer ${student}` },
}).then((x) => x.json());
if (reg.code !== 200) {
  throw new Error(`报名失败: ${JSON.stringify(reg).slice(0, 300)}`);
}

console.log(`E2E_CONTEST_ID=${contestId}`);
