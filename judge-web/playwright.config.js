import { defineConfig } from '@playwright/test';

// CodeJudge E2E 冒烟（U4/T8 第一批：登录 + 题库两条链路）。
// 目标为容器化全栈的 judge-web（nginx 同源代理 /api 与 /ws），
// 跑前确认 `docker compose --profile app up -d` 全绿。
// 登录限流为 2 req/s 突发 5 —— 测试复用登录态，禁止循环反复登录。
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [['list']],
  use: {
    baseURL: 'http://127.0.0.1:5174',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
});
