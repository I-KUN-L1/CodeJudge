import { test, expect } from '@playwright/test';

// CodeJudge E2E 冒烟第一批（HANDOFF U4/T8）：登录 + 题库两条链路。
// 串行执行且全程只登录一次：第二链路复用第一链路落下的 storageState，
// 避免撞登录限流（2 req/s 突发 5）。
// 凭据取种子学员账号（sql/seed.sql），与 verify-p1-login 同源。
test.describe.configure({ mode: 'serial' });

const PHONE = process.env.E2E_PHONE || '13900000001';
const PASSWORD = process.env.E2E_PASSWORD || '123456';

let authState;

test('登录冒烟链路：手机号+密码 → 登录成功落地题库', async ({ page }) => {
  await page.goto('/login');
  await expect(page.getByRole('heading', { name: '登录' })).toBeVisible();

  await page.getByPlaceholder('11 位手机号').fill(PHONE);
  await page.getByPlaceholder('请输入密码').fill(PASSWORD);
  await page.getByRole('button', { name: '登录' }).click();

  // 登录成功：离开 /login，落到学生端首页（题库）
  await expect(page).toHaveURL(/\/problems/, { timeout: 15_000 });
  await expect(page.locator('.auth__card')).toHaveCount(0);

  // 留下登录态给下一条链路复用（只此一次登录）
  authState = await page.context().storageState();
});

test('题库冒烟链路：题库列表可渲染且有题目数据', async ({ browser }) => {
  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto('/problems');
  await expect(page.locator('.cj-panel__table')).toBeVisible();
  const rows = page.locator('.cj-panel__table .el-table__row');
  await expect(rows.first()).toBeVisible();
  expect(await rows.count()).toBeGreaterThan(0);

  await ctx.close();
});

// ==================== U4b：剩余链路（提交 → 判题 → AI 点评 → 榜单） ====================
//
// 前置：先跑 `node e2e/setup-contest.mjs` 得到 E2E_CONTEST_ID（一场进行中的
// 演示赛 + 学员已报名）。榜单联动断言依赖本次 AC 真实计入 Redis 实时榜，
// 因此这条链路不走静态种子竞赛（那些到跑测时多半已结束、榜单为空）。
// 串行模式保证：T3 产出提交详情路径 → T4 在其上生成 AI 点评 → T5 查榜。

const CONTEST_ID = process.env.E2E_CONTEST_ID || '';

/** AC 解法必须用 long：4001 样例含 2147483647×2（int 溢出用例） */
const AC_JAVA = [
  'import java.util.*;',
  '',
  'public class Main {',
  '    public static void main(String[] args) {',
  '        Scanner sc = new Scanner(System.in);',
  '        long a = sc.nextLong();',
  '        long b = sc.nextLong();',
  '        System.out.println(a + b);',
  '    }',
  '}',
].join('\n');

/** 终态结论文案，口径同 utils/format.js 的 VERDICT 表 */
const TERMINAL_VERDICT = /通过|答案错误|超时|超内存|运行错误|编译错误|系统错误/;

let submissionPath = null; // T3 产出，T4 复用

test('提交判题链路：竞赛选题→编辑→提交→轮询判题结果', async ({ browser }) => {
  test.setTimeout(120_000);
  if (!CONTEST_ID) throw new Error('E2E_CONTEST_ID 未设置：先在 judge-web/ 内跑 node e2e/setup-contest.mjs');

  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  // 选题：进入进行中的 E2E 演示赛，确认报名态
  await page.goto(`/contests/${CONTEST_ID}`);
  await expect(page.locator('.cj-title', { hasText: '榜单联动赛' })).toBeVisible();
  await expect(page.getByText('已报名')).toBeVisible();

  // 竞赛上下文打开题目（contestId 进 query，提交才会计入实时榜）
  await page.goto(`/problems/4001?contestId=${CONTEST_ID}`);
  await expect(page.getByText('竞赛提交')).toBeVisible();
  await expect(page.locator('.editor__ta')).toBeVisible();

  // 编辑：覆盖模板代码为 AC 解法
  await page.locator('.editor__ta').fill(AC_JAVA);

  // 提交判题
  await page.getByRole('button', { name: '提交判题' }).click();

  // 判题结果：轮询页面状态到终态（WS 推送为主，「刷新状态」REST 兜底，不写死 sleep）
  const verdictTag = page.locator('.prog-line .verdict');
  await expect(verdictTag).toBeVisible({ timeout: 15_000 });
  await expect(async () => {
    const current = (await verdictTag.innerText()).trim();
    if (!TERMINAL_VERDICT.test(current)) {
      const refresh = page.getByRole('button', { name: '刷新状态' });
      if (await refresh.isVisible().catch(() => false)) await refresh.click();
    }
    expect(
      TERMINAL_VERDICT.test((await verdictTag.innerText()).trim()),
      `等待判题终态，当前：${current}`
    ).toBe(true);
  }).toPass({ timeout: 90_000, intervals: [2_000, 3_000] });
  await expect(verdictTag).toHaveText('通过');

  // 判题详情：逐用例结果表渲染
  await page.getByRole('button', { name: '查看判题详情' }).click();
  await expect(page).toHaveURL(/\/submissions\/\d+/);
  submissionPath = new URL(page.url()).pathname;
  await expect(page.locator('.cj-card__head', { hasText: '用例结果' })).toBeVisible();
  await expect(page.locator('.cj-card__head .verdict').first()).toHaveText('通过');
  expect(await page.locator('.el-table__row').count()).toBeGreaterThanOrEqual(2);

  await ctx.close();
});

test('AI 点评链路：判题详情页生成点评并流式出正文', async ({ browser }) => {
  test.setTimeout(150_000);
  if (!submissionPath) throw new Error('前置提交链路未产出提交详情路径');
  // 断言口径：点评**链路**（按钮 → SSE 流式 → 正文渲染 → 收尾状态）。
  // 上游 LLM 限流（429）时产品按设计降级为模板内容，链路断言仍应通过；
  // 「真实 AI 文本」是外部依赖，单独在执行报告核对（见 2026-10-05 U4b 证据）。

  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto(submissionPath);
  await expect(page.locator('.cj-card__head', { hasText: 'AI 代码点评' })).toBeVisible();
  await page.getByRole('button', { name: '生成 AI 点评' }).click();

  // 首帧正文（RAG 检索事件先于正文推送；LLM 首 token 需 5–20s）
  const aiBody = page.locator('.ai__body');
  await expect(aiBody).toBeVisible({ timeout: 60_000 });

  // 流式收尾：状态文案进入「生成完成」，正文非空
  await expect(page.locator('.ai__bar').getByText('生成完成')).toBeVisible({ timeout: 120_000 });
  expect((await aiBody.innerText()).trim().length).toBeGreaterThan(20);

  await ctx.close();
});

test('榜单链路：实时榜出现本次 AC 的参赛者', async ({ browser }) => {
  test.setTimeout(60_000);
  if (!CONTEST_ID) throw new Error('E2E_CONTEST_ID 未设置：先在 judge-web/ 内跑 node e2e/setup-contest.mjs');

  const ctx = await browser.newContext({ storageState: authState });
  const page = await ctx.newPage();

  await page.goto(`/contests/${CONTEST_ID}`);
  await expect(page.locator('.cj-card__head', { hasText: '排行榜' })).toBeVisible();

  // WS SNAPSHOT 已渲染（表格离开 v-loading，出现数据行）
  const rankRows = page.locator('.el-table__row');
  await expect(rankRows.first()).toBeVisible({ timeout: 20_000 });

  // 本次链路的 AC 已联动计入：演示学员一 过 1 题
  const myRow = page.locator('.el-table__row', { hasText: '演示学员一' }).first();
  await expect(myRow).toBeVisible();
  await expect(myRow).toContainText('过 1 题');

  await ctx.close();
});
