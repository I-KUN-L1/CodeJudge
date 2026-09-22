# ============================================================
# judge-gcc13 —— C/C++ 判题镜像（GCC 13）
#
# 用途：编译 (g++ -O2 -std=c++17) + 运行。
# 构建：python scripts/build-sandbox-images.py
# ============================================================
FROM gcc:13

# 基础镜像可能自带 GID/UID 1000，按 id 幂等创建
RUN if ! getent group 1000 >/dev/null; then groupadd -g 1000 judge; fi \
    && if ! getent passwd 1000 >/dev/null; then useradd -m -u 1000 -g 1000 judge; fi

WORKDIR /work
