# ============================================================
# judge-web 前端 —— node 构建 -> nginx 托管（非 root）
# 构建上下文为 judge-web 目录：
#   docker build -f judge-web/Dockerfile -t codejudge/judge-web:latest judge-web/
#
# 运行时同源代理约定（与 src/api/base.js 的 VITE_API_BASE_URL=/api 模式对应）：
#   REST  /api/**  -> http://judge-gateway:9080/**（去 /api 前缀）
#   WS    /ws/**   -> http://judge-gateway:9080/ws/**（WS_BASE 按页面主机推导）
#   SSE   /api/ai/** 依赖 /api 反代，且必须关闭缓冲（见 nginx.conf sse 路由）
# ============================================================
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM nginx:alpine
# nginx 官方镜像以 root 启动 master、worker 以 nginx 用户运行；80 为特权端口故保留 master=root
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
  CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
CMD ["nginx", "-g", "daemon off;"]
