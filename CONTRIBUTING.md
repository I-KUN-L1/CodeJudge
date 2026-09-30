# 贡献指南（CONTRIBUTING）

## 分支命名

| 分支 | 用途 |
|---|---|
| `master` | 主干，始终保持可构建、verify 套件全绿 |
| `feat/<主题>` | 新功能（如 `feat/special-judge`） |
| `fix/<主题>` | 缺陷修复 |
| `chore/<主题>` | 依赖升级、构建、文档 |

## 提交规范（Conventional Commits）

```
<type>(<scope>): <一句话摘要，中文>

[正文：动机、方案要点、影响面]
```

- type：`feat` / `fix` / `refactor` / `test` / `docs` / `build` / `chore`
- scope：模块名（judge-auth、judge-web、deploy …）
- 破坏性变更必须在正文首行写 `BREAKING CHANGE: <说明>`
- 示例：`fix(judge-worker): 沙箱墙钟强杀后补 docker rm -f（容器残留）`

## PR 流程

1. 从 `master` 拉分支；一个 PR 只做一件事。
2. 本地必须先过（见下节清单）。
3. CI 全绿（`mvn verify` + 前端 vitest/build）才能合并。
4. 涉及以下面的改动需要额外复验对应验收脚本：

| 改动面 | 必跑 |
|---|---|
| 登录 / token / 网关白名单 | `python scripts/verify-p1-login.py` + `python scripts/verify-authz.py` |
| 判题链路 / 沙箱 | `python scripts/verify-p3.py` |
| 竞赛 / 榜单 / WS | `python scripts/verify-p4.py` |
| AI 点评 | `python scripts/verify-p5.py` |
| 监控 / 指标 | `python scripts/verify-p6.py` |
| 前端 | `cd judge-web && npm test && npm run build` |

## 本地验证清单（PR 前）

```bash
mvn -B verify                          # 编译 + 单测 + JaCoCo 门禁
cd judge-web && npm ci && npm test -- --run && npm run build
docker-compose config && docker-compose --profile app config
```

## 其他约定

- **不要**绕过既有编排脚本另起炉灶：启动一律 `python startup.py`（或透传），
  编排真相在 `scripts/start-all.py`；schema 变更走 Flyway 迁移文件（`V<N>__xxx.sql`），不改 `V1__baseline.sql`。
- 密钥零硬编码：一律 `CJ_*` 环境变量；判据 `python scripts/check-hardcoded-defaults.py`。
- 验收脚本禁止假绿：`all(...)` 断言必须同时断言样本非空（docs/CONTEXT.md §3.16）。
