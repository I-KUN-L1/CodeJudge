# ============================================================
# judge-python3.12 —— Python 3.12 判题镜像
#
# 用途：解释执行 (python3 -I)。无编译阶段（语言层面无 CE）。
# 构建：python scripts/build-sandbox-images.py
# ============================================================
FROM python:3.12-slim

# 基础镜像可能自带 GID/UID 1000，按 id 幂等创建
RUN if ! getent group 1000 >/dev/null; then groupadd -g 1000 judge; fi \
    && if ! getent passwd 1000 >/dev/null; then useradd -m -u 1000 -g 1000 judge; fi

# -I 隔离模式由 worker 的运行命令注入（不读用户 site-packages、不把 cwd 加入 sys.path）
WORKDIR /work
