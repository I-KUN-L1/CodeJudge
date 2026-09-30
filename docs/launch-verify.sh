#!/usr/bin/env bash
# CodeJudge 上线检查一键验收（只读，不修改文件）
# 运行：bash docs/launch-verify.sh
# 注：路径以本仓实际布局为准（告警规则在 prometheus/rules/ 下，含 11 条实测重标规则）
set -u
PASS=0; FAIL=0
check() { if eval "$2" >/dev/null 2>&1; then echo "OK   $1"; PASS=$((PASS+1)); else echo "MISS $1"; FAIL=$((FAIL+1)); fi; }

echo "== A. 部署产物 =="
for m in judge-gateway judge-auth judge-user judge-problem judge-submission judge-worker judge-contest judge-ai; do
  check "$m/Dockerfile" "test -f $m/Dockerfile"
done
check "judge-web/Dockerfile"   "test -f judge-web/Dockerfile"
check "judge-web/nginx.conf"   "test -f judge-web/nginx.conf"
check "startup.sh"             "test -f startup.sh"
check ".dockerignore"          "test -f .dockerignore"
check "优雅停机(8服务)"         "[ \$(grep -l 'shutdown: graceful' judge-*/src/main/resources/application.yml 2>/dev/null | wc -l) -eq 8 ]"
check "compose stop_grace_period" "grep -q stop_grace_period docker-compose.yml"

echo "== B. CI/CD =="
check ".github/workflows/ci.yml" "test -f .github/workflows/ci.yml"
check "README 徽章"              "grep -q 'shields.io' README.md"
check "JaCoCo check 门禁"        "grep -q 'goal>check' pom.xml"
check "SpotBugs/Checkstyle"      "grep -qE 'spotbugs|checkstyle' pom.xml"
check "CI 依赖扫描(osv)"         "grep -q 'osv-scanner' .github/workflows/ci.yml"

echo "== C. 数据库迁移 =="
check "Flyway 迁移目录(6服务)"   "[ \$(ls judge-*/src/main/resources/db/migration/V1__baseline.sql 2>/dev/null | wc -l) -eq 6 ]"
check "Flyway baseline 配置"     "[ \$(grep -l 'baseline-on-migrate' judge-*/src/main/resources/application.yml 2>/dev/null | wc -l) -eq 6 ]"
check "备份脚本"                 "test -f scripts/backup-db.py"
check "增长治理文档"             "test -f docs/DATA-GROWTH.md"

echo "== D. 可观测性 =="
check "告警规则(11条,实测重标)"  "test -f deploy/monitoring/prometheus/rules/codejudge-alerts.yml"
check "Grafana 看板 >= 8"        "[ \$(ls deploy/monitoring/grafana/dashboards/*.json 2>/dev/null | wc -l) -ge 8 ]"
check "Loki 配置"                "test -f deploy/monitoring/loki/loki-config.yml"
check "SLO 文档"                 "grep -qE '99\.9|P99' docs/SLO.md 2>/dev/null"

echo "== E. 安全 =="
check "CORS 非通配(patterns)"    "grep -q 'allowedOriginPatterns' judge-gateway/src/main/resources/application.yml"
check "TLS 章节"                 "grep -q '8.1 HTTPS' docs/DEPLOYMENT.md"
check "JWT 轮换步骤"             "grep -q 'JWT 密钥轮换' docs/DEPLOYMENT.md"
check "沙箱 digest 固定"         "grep -q 'BASE_DIGESTS' scripts/build-sandbox-images.py"

echo "== F/G. 高可用与测试 =="
check "验收脚本 p1-p6+authz"     "test -f scripts/verify-p3.py -a -f scripts/verify-authz.py"
check "故障转移验证脚本"         "test -f scripts/verify-p3-failover.py"

echo "== I/J/K. 工程化 =="
check "PG schema 自愈脚本"       "test -f scripts/reset-pg-schema.py"
check "队列对账文档"             "grep -q '队列对账' docs/ARCHITECTURE.md"
check "CONTRIBUTING.md"          "test -f CONTRIBUTING.md"
check "CHANGELOG.md"             "test -f CHANGELOG.md"
check "SECURITY.md"              "test -f SECURITY.md"
check "ROADMAP.md"               "test -f docs/ROADMAP.md"
check ".editorconfig"            "test -f .editorconfig"
check ".gitattributes"           "test -f .gitattributes"
check "gitignore 覆盖敏感文件"   "grep -q bootstrap-credentials .gitignore && grep -q '^\.env' .gitignore"

echo
echo "PASS=$PASS  FAIL=$FAIL"
[ $FAIL -eq 0 ] && echo "✅ 全部门禁通过" || echo "❌ 仍有 $FAIL 项未完成"
exit $FAIL
