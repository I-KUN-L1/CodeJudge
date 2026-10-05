# 2026-10-05 遗留跟进结案：tomcat-embed-core 10.1.55 → 10.1.59（CVE-2026-65182）

| 文件 | 来源 | 说明 |
|---|---|---|
| `central-metadata-excerpt.txt` | `https://repo1.maven.org/maven2/org/apache/tomcat/embed/tomcat-embed-core/maven-metadata.xml` | Central 版本列：`10.1.57` 之后直接 **`10.1.59`**（10.1.58 被官方跳过未发布）→ HANDOFF 触发条件「出现 10.1.58+」达成 |
| `trivy-fs-judge-user-m2.txt` | `docker run ghcr.io/aquasecurity/trivy fs --db-repository ghcr.io/aquasecurity/trivy-db -v <模块>:/scan:ro -v ~/.m2:/root/.m2:ro`（judge-user，servlet 服务代表） | **tomcat-embed-core 零命中 → CVE-2026-65182 清除**；Total 61 = HIGH 25 / CRITICAL 1（唯一 CRITICAL 为 fastjson 1.2.69_noneautotype，U3 已定性误报：11 模块 dependency:tree 零命中） |
| `images-build.log` | `python scripts/build-app-images-prebuilt.py --worker-docker-cli` | 根 pom `tomcat.version=10.1.59` → `mvn install` 后 8 张运行时镜像全部重建 |
| `verify-p1-login-43-0.txt` | `python scripts/verify-p1-login.py` | 重部署后回归 **43 通过 / 0 失败** |
| `e2e-post-upgrade.log` | `cd judge-web && npm run test:e2e` | 升版端到端回归 **5/5 passed (21.4s)**（含真实判题 AC，沙箱链路正常） |

## 处置链（升版 → 复扫 → 重部署 → 回归）

1. 根 pom `<tomcat.version>` 10.1.55 → 10.1.59，注释同步更新。
2. `mvn install -DskipTests -Djacoco.skip=true` 全 11 模块 BUILD SUCCESS（纯钉版变更；CI 不跳过 JaCoCo/测试）。
   产物核验：6 个 servlet 服务（auth/contest/problem/submission/user/worker）`BOOT-INF/lib/tomcat-embed-core-10.1.59.jar`；
   judge-ai / judge-gateway 为 WebFlux/Netty，仅 tomcat-embed-el-10.1.59（无 core，符合预期）。
3. trivy 复扫：tomcat-embed-core 无命中（见上表）。
4. `docker compose --profile app up -d` 8 服务重建 healthy；compose 依赖同时重跑 sandbox-work-init（Exited 0 属预期）。
5. 回归：verify-p1-login 43/0 + E2E 5/5。

## 结论

DEPLOYMENT.md §8.4.1 中「CVE-2026-65182：接受 + CI 复扫跟进」登记项**结案**。
