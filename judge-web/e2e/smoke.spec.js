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
