import { fileURLToPath, URL } from 'node:url';
import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

/**
 * CodeJudge 前端构建配置
 *
 * 关于请求地址（重要，不要随手改）：
 *   前端**默认直连网关**，而不是走 dev server 代理。原因：
 *     1. 网关已配置 `allowedOriginPatterns`（localhost + 127.0.0.1，端口通配）并允许凭据，
 *        跨域是**已支持的一等路径**，绕开它反而让生产部署（前后端分域）从未被验证过；
 *     2. SSE（AI 点评）与 WebSocket 都是长连接，经代理多一层缓冲/超时风险，
 *        排障时无法区分"是代理还是后端"。
 *
 *   `/api` 代理作为**备选方案**保留：某些环境不允许改网关 CORS 时，
 *   设 `VITE_API_BASE_URL=/api` 即可改为同源访问（见 .env.example）。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5174,
    // strictPort：端口被占时直接失败，而不是静默递增到 5175 ——
    // 端口漂移会让网关 CORS 白名单（端口通配）之外的反向代理/文档失效，排查成本高。
    strictPort: true,
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:9080',
        changeOrigin: true,
        ws: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    // Element Plus + ECharts 打包后单块会超过默认 500KB 提示线，调高避免噪音告警
    chunkSizeWarningLimit: 1600,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia', 'axios'],
          ui: ['element-plus', '@element-plus/icons-vue'],
          charts: ['echarts'],
          md: ['markdown-it', 'dompurify', 'highlight.js'],
        },
      },
    },
  },
});
