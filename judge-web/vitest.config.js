import { defineConfig, mergeConfig } from 'vitest/config';
import viteConfig from './vite.config.js';

/**
 * 单测配置（独立文件，不改动 vite.config.js）
 *
 * 为什么单独一份而不是往 vite.config.js 里塞 test 字段：
 *   构建配置是生产路径的一部分，被 CI 的 build 步骤直接消费；测试环境（jsdom、
 *   覆盖率、globalSetup）与构建无关，混在一起会让"为了跑测试动到产物配置"成为
 *   一次可能的线上风险。mergeConfig 复用别名与插件，保证 `@/` 在测试与构建中
 *   解析结果一致 —— 否则会出现"构建能过、测试找不到模块"的假象。
 */
export default mergeConfig(
  viteConfig,
  defineConfig({
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['tests/**/*.spec.js'],
      setupFiles: ['tests/setup.js'],
      // 每个用例前重置 spy/mock，避免跨文件污染（尤其 fetch / axios 这类全局替换）
      restoreMocks: true,
      coverage: {
        provider: 'v8',
        reporter: ['text', 'html'],
        include: ['src/**/*.{js,vue}'],
        // 入口与纯样式聚合文件不纳入统计，否则覆盖率数字被稀释得没有参考价值
        exclude: ['src/main.js', 'src/App.vue', 'src/**/*.css'],
      },
    },
  }),
);
