# 贡献指南（Contributing to CodeJudge）

感谢你对 CodeJudge 的关注！本项目虽为个人学习项目，同样欢迎 Issue 反馈、文档纠错与功能 PR。请花一分钟阅读以下约定。

## 🌿 分支命名

`master` 为唯一长期分支，功能开发请从最新 `master` 拉出**短生命周期**分支，命名 `<type>/<主题>`：

| 分支前缀 | 用途 | 示例 |
|---|---|---|
| `feat/` | 新功能 | `feat/special-judge` |
| `fix/` | 缺陷修复 | `fix/sandbox-wallclock-kill` |
| `docs/` | 文档 | `docs/api-reference` |
| `refactor/` | 重构（不改行为） | `refactor/rank-lua` |
| `chore/` | 构建 / 依赖 / CI | `chore/jacoco-ratchet` |

## 📝 提交规范（Conventional Commits）

```
<type>(<scope 可选>): <中文一句话主题>
```

- **type**：`feat` / `fix` / `docs` / `style` / `refactor` / `perf` / `test` / `build` / `ci` / `chore` / `revert`
- **scope**：模块名小写（如 `worker`、`contest`、`web`），跨模块可省略
- **主题**：中文动宾短语，结尾不加句号；一次提交只做一件事
- 破坏性变更必须在正文首行写 `BREAKING CHANGE: <说明>`
- 与仓库现有提交风格保持一致，例如：

```
feat(contest): 封榜窗口 ZSet 快照视图
fix(worker): 沙箱墙钟强杀后补 docker rm -f（容器残留）
docs: 修正 PERF.md 中指向已移出仓库材料的引用
```

## 🔄 PR 流程

1. **Issue 先行**：较大改动（新模块、架构调整、新增依赖）请先开 Issue 对齐方案，小改动可直接提 PR
2. **本地验证**：提交前跑通 CI 同款命令

   ```bash
   mvn -B verify                                  # 编译 + 单测 + JaCoCo 门禁
   cd judge-web && npm ci && npm test -- --run && npm run build
   docker-compose config && docker-compose --profile app config
   ```

3. **发起 PR**：向 `master` 提交，标题遵循上述提交规范；描述写清 **动机 / 改动点 / 验证方式**
4. **CI 检查**：GitHub Actions（JDK 21 后端构建 + 前端 vitest/build + osv-scanner）必须绿勾
5. **定向复验**：涉及以下面的改动，合并前需额外复验对应验证脚本：

| 改动面 | 必跑 |
|---|---|
| 登录 / token / 网关白名单 | `python scripts/verify-p1-login.py` + `python scripts/verify-authz.py` |
| 判题链路 / 沙箱 | `python scripts/verify-p3.py` |
| 竞赛 / 榜单 / WS | `python scripts/verify-p4.py` |
| AI 点评 | `python scripts/verify-p5.py` |
| 监控 / 指标 | `python scripts/verify-p6.py` |

6. **Review 合并**：维护者 review 通过后 squash merge，分支随之删除

## 🚫 提交红线

- 勿提交 `.env`、`.bootstrap-credentials`、日志、压测原始数据（`.gitignore` 已拦截，请勿强行 `git add -f`）
- 勿硬编码任何密码 / 密钥，一律走 `CJ_*` 环境变量（`.env.example` 有模板；判据 `python scripts/check-hardcoded-defaults.py`）
- 勿绕过 CI（禁止 `--no-verify` 跳过钩子）
- 验证脚本禁止假绿：断言必须同时断言样本非空

## 🧭 其他约定

- 启动一律走 `python startup.py` / `scripts/start-all.py`（编排真相在 `start-all.py`），不要绕过编排脚本另起炉灶
- schema 变更走 Flyway 迁移文件（`V<N>__xxx.sql`），不改 `V1__baseline.sql`
- Bug 反馈请附：复现步骤、期望/实际行为、相关日志（脱敏后）
- 文档类贡献（错别字、示例修正、FAQ 补充）尤其欢迎，零门槛
