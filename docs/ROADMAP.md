# Roadmap（上线检查清单 K 区）

> P2 长期优化项的排期视图。排序原则：先做「放大已有能力」的，再做「新能力」的。

## 近期（上线后 1–2 个月）

- [ ] **代码相似度检测（防抄袭）**——教育场景核心卖点。方案候选：
      ① 判题后对通过代码做 AST 指纹（jplag 类算法）；② token n-gram 指纹入 PG，复用 pgvector 检索通道。
- [ ] **special judge**——支持自定义校验器（浮点误差、多解、SPJ）。需要 worker 沙箱加「校验器容器」形态。
- [ ] **SLO / 错误预算落地**——`docs/SLO.md` 已定义；补 `cj_judge_e2e_seconds` 埋点与 Grafana 预算看板。
- [ ] **OpenTelemetry 全链路**——网关 traceId 生成 → Feign/WN 透传 → judge-ai SSE 带回；后端 Jaeger/Tempo。

## 中期（3–6 个月）

- [ ] **subtask 判题**（部分分）、**交互题**（stdin/stdout 对话式）——沙箱需支持长会话容器。
- [ ] **题解 / 讨论区**——新服务或并入 judge-problem；内容安全（敏感词/审核）先设计。
- [ ] **JWT 多密钥（kid）+ refresh token 轮换吊销（Redis jti）+ RS256 化**——
      三件事共享密钥管理基建，合并立项（见 `DEPLOYMENT.md` §8.2）。
- [ ] **日志 JSON 化**——judge-common 统一 logback-spring.xml（含滚动），Promtail 切 JSON 解析。
- [ ] **多租户 / 组织隔离**——数据面（org_id 列 + 归属守卫扩展）与应用面（能力码按租户裁剪）。

## 远期（6 个月+）

- [ ] **判题重放 / 回放**——用例输入 + 编译产物快照，支持事后仲裁与教学演示。
- [ ] **蓝绿 / 金丝雀发布**——依赖 compose → K8s 迁移（探针/HPA 配置见 LAUNCH-CHECKLIST F2）。
- [ ] **容量规划与成本模型**——基于 `docs/PERF.md` 实测吞吐推导「N 千学员需要几台判题机」的计算表。
- [ ] **K8s 迁移**——app profile 的 compose 定义可翻译为 Deployment（worker 的 docker.sock 派发需改为
      K8s Job 或 DinP，这是迁移最大阻力点）。

## 技术债（随项偿还）

| 债 | 出处 |
|---|---|
| RocketMQ broker 无 store 卷（消息不持久化） | docker-compose.yml 注释 |
| MinIO 在 storage profile 未默认启动 | CONTEXT §7 |
| refresh token 无轮换/吊销 | REVIEW-2026-09-25 §G |
| Python 语法错误判 RE 非 CE（缺 py_compile 预检） | 第六轮记录 |
| Java 堆内 MLE 判不出 | 第六轮记录 |
