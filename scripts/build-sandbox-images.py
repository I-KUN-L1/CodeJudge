#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeJudge 判题沙箱镜像构建脚本（P3 前置条件）。

构建 4 个语言判题镜像（judge-java21 / judge-python3.12 / judge-gcc13 / judge-go1.22）。
本机 .sh 无法执行（路由到 wsl.exe 撞黑名单），故脚本一律用 .py。

用法（在仓库根目录）：
    python scripts/build-sandbox-images.py           # 构建全部 4 个
    python scripts/build-sandbox-images.py java cpp  # 只构建指定语言

镜像名与 .env 的 CJ_SANDBOX_IMAGE_* 默认值对齐；如改过 .env 中的镜像名，
本脚本读取 .env 里的 CJ_SANDBOX_IMAGE_* 作为 tag。
"""

import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 语言 -> (Dockerfile 目录, 默认镜像 tag, 基础镜像, 用途)
IMAGES = {
    "java":   ("sandbox/docker/judge-java21",   "judge-java21:latest",   "eclipse-temurin:21-jdk", "Java 21 编译+运行"),
    "python": ("sandbox/docker/judge-python3.12", "judge-python3.12:latest", "python:3.12-slim", "Python 3.12 解释执行"),
    "cpp":    ("sandbox/docker/judge-gcc13",    "judge-gcc13:latest",    "gcc:13",                 "GCC 13 编译+运行"),
    "go":     ("sandbox/docker/judge-go1.22",   "judge-go1.22:latest",   "golang:1.22",            "Go 1.22 编译+运行"),
}

# .env 中 CJ_SANDBOX_IMAGE_* 与语言 key 的映射
ENV_KEYS = {
    "java": "CJ_SANDBOX_IMAGE_JAVA",
    "python": "CJ_SANDBOX_IMAGE_PYTHON",
    "cpp": "CJ_SANDBOX_IMAGE_CPP",
    "go": "CJ_SANDBOX_IMAGE_GO",
}


def read_env_tags():
    """从仓库根目录的 .env 读取 CJ_SANDBOX_IMAGE_*（存在则覆盖默认 tag）。"""
    tags = {}
    env_file = os.path.join(ROOT, ".env")
    if os.path.exists(env_file):
        with open(env_file, "r", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if "=" not in line or line.startswith("#"):
                    continue
                key, _, value = line.partition("=")
                for lang, env_key in ENV_KEYS.items():
                    if key.strip() == env_key:
                        tags[lang] = value.strip()
    return tags


def sh(cmd):
    print("+ " + " ".join(cmd))
    return subprocess.run(cmd, cwd=ROOT).returncode


def docker_available():
    try:
        return sh(["docker", "version", "--format", "{{.Server.Version}}"]) == 0
    except FileNotFoundError:
        return False


def build(lang, tag):
    dockerfile_dir, _, base, purpose = IMAGES[lang]
    print(f"\n===== 构建 {tag}（{purpose}）=====")
    # 先拉基础镜像：失败时给出清晰提示（受限网络可能拒绝拉取）
    if sh(["docker", "pull", base]) != 0:
        print(f"[错误] 基础镜像拉取失败：{base}。请检查网络/镜像仓库配置后重试。")
        return False
    return sh(["docker", "build", "-t", tag, dockerfile_dir]) == 0


def main():
    selected = sys.argv[1:] or list(IMAGES.keys())
    selected = [s.lower() for s in selected]
    unknown = [s for s in selected if s not in IMAGES]
    if unknown:
        print(f"未知语言：{unknown}；可选：{list(IMAGES.keys())}")
        sys.exit(2)

    if not docker_available():
        print("[错误] docker 不可用。请确认 Docker Desktop 已启动。")
        sys.exit(1)

    env_tags = read_env_tags()
    failed = []
    for lang in selected:
        tag = env_tags.get(lang) or IMAGES[lang][1]
        if not build(lang, tag):
            failed.append(lang)

    print("\n===== 构建结果 =====")
    if failed:
        print(f"失败：{failed}")
        sys.exit(1)
    print("全部成功。可用 `docker images | grep judge-` 查看，然后启动 judge-worker。")


if __name__ == "__main__":
    main()
