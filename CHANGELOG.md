# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [Unreleased]

### Added（系统功能演示账户 2026-10-07 轮）
- 代码草稿：`judge_submission` 新增 `code_draft` 表（Flyway `V2__code-draft.sql`，
  存量手工迁移 `sql/migrations/2026-10-07-code-draft.sql`）与
  `GET/PUT/DELETE /submissions/drafts/{problemId}`（仅本人，物理删，提交成功后消费）；
  前端题目详情页编辑器优先载入草稿，无草稿回退题目模板
- 演示账户种子：`sql/seed.sql` 新增演示学生（2999，全部已发布题预填"待提交标准答案"草稿，
  提交走完整真实判题链路）与演示教师（2110，已编辑完成待发布的题目 4021/4022 + 未开始的
  竞赛 5003；竞赛无独立发布态，未开始即演示形态）
- 凭据文件：演示账号信息追加写入 `.bootstrap-credentials`（与首个管理员共用一份）；
  管理员/演示学生/演示教师任一改密成功后整份文件自动删除 —— 演示账号普通改密由
  judge-user 经 `AuthClient` 回调 judge-auth 内部端点 `POST /internal/bootstrap/credential-consumed`
  实现（fire-and-forget，judge-auth 不可达不影响改密主流程）
- 运维脚本：`scripts/verify-demo-accounts.py`（端到端验证 + 自动恢复）、
  `scripts/restore-demo-state.py`（演示数据一键恢复初始状态）、
  `generate-bootstrap-credentials.py` 支持演示账号区段

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
