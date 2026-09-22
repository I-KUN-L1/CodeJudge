# ============================================================
# judge-java21 —— Java 21 判题镜像
#
# 用途：编译 (javac) + 运行 (java)。由 judge-worker 以 docker run 调用，
# 安全约束（只读根文件系统/断网/资源限额/非 root）由 worker 侧的
# docker run 参数注入，不在镜像内固化。
#
# 构建：python scripts/build-sandbox-images.py
# ============================================================
FROM eclipse-temurin:21-jdk

# 与 worker 侧 --user 1000:1000 对齐（运行时以 uid 1000 执行用户代码）
# 注意：基础镜像可能自带 GID/UID 1000（如 ubuntu 组/用户），按 id 幂等创建
RUN if ! getent group 1000 >/dev/null; then groupadd -g 1000 judge; fi \
    && if ! getent passwd 1000 >/dev/null; then useradd -m -u 1000 -g 1000 judge; fi

# PATH 默认已含 JDK；无其他定制 —— 镜像保持最小化，行为全部由运行时参数控制
WORKDIR /work
