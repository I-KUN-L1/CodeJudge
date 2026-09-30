# ============================================================
# judge-gateway —— 多阶段构建：Maven 编译 -> JRE 运行（非 root）
# 构建上下文必须是仓库根目录：
#   docker build -f judge-gateway/Dockerfile -t codejudge/judge-gateway:latest .
# ============================================================
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src

# 依赖缓存层：先只拷 pom，pom 不变时依赖解析层直接命中缓存
COPY pom.xml .
COPY judge-api/pom.xml judge-api/
COPY judge-common/pom.xml judge-common/
COPY judge-gateway/pom.xml judge-gateway/
RUN mvn -q -B -pl judge-gateway -am dependency:go-offline

# 编译层：拷源码打包（-am 连带构建 judge-common / judge-api）
COPY judge-api judge-api
COPY judge-common judge-common
COPY judge-gateway judge-gateway
RUN mvn -q -B -pl judge-gateway -am package -DskipTests

# ---------- 运行时 ----------
FROM eclipse-temurin:21-jre
# curl 仅用于容器健康检查（/actuator/health）
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
# 非 root 运行（uid 1001 固定，便于日志/卷权限规划）
RUN useradd --system --uid 1001 --shell /usr/sbin/nologin judge \
    && mkdir -p /app && chown judge:judge /app
USER judge
WORKDIR /app
COPY --from=build /src/judge-gateway/target/judge-gateway-*.jar /app/app.jar
EXPOSE 9080
# 优雅停机：application.yml 已配 server.shutdown=graceful + 30s phase 超时，
# 编排侧需配合 stop_grace_period >= 40s（见 docker-compose.yml app 段）
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:9080/actuator/health || exit 1
