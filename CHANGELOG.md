# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Added（上线检查清单 2026-09-30 轮）
- 部署产物：8 后端 + 前端 Dockerfile、`judge-web/nginx.conf`（SPA/反代/WS/SSE）、
  compose `app` profile（9 服务，`stop_grace_period` 40s）、`startup.sh` / `startup.ps1`
- CI：`.github/workflows/ci.yml`（JDK21 构建+单测+JaCoCo artifact、前端 vitest+dist artifact、osv-scanner）
- 覆盖率门禁：JaCoCo `check` LINE 0.04 起步（实测基线见根 pom 注释，棘轮式抬升至 0.30）
- 静态检查（仅报告）：SpotBugs / Checkstyle 绑定 verify
- Flyway 接管 schema：6 个有库服务 `V1__baseline.sql`（与 `sql/init.sql` 同源）+ `baseline-on-migrate`
- 备份：`scripts/backup-db.py`（MySQL/PG 全量 + Redis 持久化检查 + 保留策略）
- 可观测：Grafana 看板 4→8（判题机集群/沙箱资源/MQ 延迟/竞赛榜单）；Loki+Promtail 日志聚合接入监控栈
- 文档：`docs/SLO.md`（6 项 SLO + 错误预算策略）、`docs/DATA-GROWTH.md`（增长治理）、
  `DEPLOYMENT.md` §8（TLS/JWT 轮换/API 版本化）
- 工程化：CONTRIBUTING / CHANGELOG / SECURITY / .editorconfig / .gitattributes / .dockerignore
- `docs/launch-verify.sh` 一键验收（只读）

### Fixed
- `judge-contest` 测试文件 UTF-8 BOM 导致 `mvn verify` 编译失败（`\ufeff` 非法字符；
  增量编译曾掩盖该问题——单独 test-compile 命中旧 class 误判可过）

## [1.0.0] - 2026-09-25

### Added
- P1–P6 全量交付：8 个后端微服务（9080–9087）+ Vue 前端（5174）+ 监控栈（9090/9093/3001）
  + 4 语言沙箱镜像 + JMeter 压测体系 + 完整文档
- 鉴权收敛：单登录入口 + 能力码按钮权限（`verify-authz.py` 56 项）
- 第八轮上线前安全审查收口（`/jwks` 删除、token 吊销、IDOR、ORDER BY 注入等，
  详见 `docs/REVIEW-2026-09-25.md`）
