# 2026-10-04 第九轮：依赖升级重部署 + 运行时回归 证据索引

| 文件 | 来源 | 说明 |
|---|---|---|
| `image-build-prebuilt.log` | `scripts/build-app-images-prebuilt.py --worker-docker-cli` 后台任务全量输出（job-53f128ab） | 8 服务镜像从本地 jar 构建，8/8 `[OK]`，`script_exit=0`（多阶段 Dockerfile 因 docker.io 被墙不可用，改走 prebuilt 路径） |
| `docker-ps-redeploy.txt` | `docker compose --profile app up -d` 后快照 | 20 容器在线，8 服务全部 `healthy` |
| `docker-images-codejudge.txt` | `docker images` 快照 | 8 个 `codejudge/*:latest` 镜像创建时间=重建时刻，即含升级后依赖（tomcat 10.1.55 / netty 4.1.137.Final / bcprov 1.85） |
| `docker-stats-redeploy.txt` | `docker stats --no-stream` 快照 | 重部署后各容器 CPU/内存基线（系统状态参数） |
| `health-probe.txt` | 宿主 `GET /actuator/health` 直探 9080–9087 | 9081–9087 全 200；9080 对外 404 为 `ActuatorGuardFilter` 严格模式预期（容器内健康检查=healthy） |
| `verify-p1-login-redeploy.log` | `scripts/verify-p1-login.py` 重放全录 | **43/0 全过**（新运行时回归；含临时账号创建/清理全程时间戳） |

## 关联登记
- 处置全文：`docs/DEPLOYMENT.md` §8.4.1；扫描原始证据：`../2026-10-04-trivy/`、`../2026-10-04-zap/`
- 提交：dbb32e7（升级+重部署+本目录首版证据）、0196b31（第九轮文档同步）
- 性能测试证据（1h soak）：`docs/perf-evidence/2026-10-04-soak-1h/`（becf8be）
