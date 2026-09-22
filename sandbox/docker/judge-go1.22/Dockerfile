# ============================================================
# judge-go1.22 —— Go 1.22 判题镜像
#
# 用途：单文件编译 (go build，GOPROXY=off 断网构建，仅标准库) + 运行。
# 构建：python scripts/build-sandbox-images.py
# ============================================================
FROM golang:1.22

# 基础镜像可能自带 GID/UID 1000，按 id 幂等创建
RUN if ! getent group 1000 >/dev/null; then groupadd -g 1000 judge; fi \
    && if ! getent passwd 1000 >/dev/null; then useradd -m -u 1000 -g 1000 judge; fi

# GOCACHE 由 worker 在运行时指向容器内可写 tmpfs（/work/.gocache）
ENV GOFLAGS="-mod=mod"
WORKDIR /work
