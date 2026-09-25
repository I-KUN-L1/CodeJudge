#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
check-hardcoded-defaults.py —— 「零硬编码」静态自检（不读任何凭据文件）

背景
----
项目硬约束 #3 要求：**所有密码 / JWT 密钥 / LLM Key 走环境变量**（前缀 `CJ_*`），
零硬编码。`.env` 已 gitignore，但**代码与配置文件里的兜底默认值**不入 gitignore ——
它们才是真正会被带进生产的风险面。

2026-09-22 首次提交预演时用人工方式扫到两处 `@Value` 兜底口令，遂把它固化成脚本，
避免以后靠"记得手动 grep"。

判定分两层（刻意不同，否则会变成噪音）
------------------------------------
* **FAIL** —— `resources/` 或 `deploy/` 的 yml 里出现**字面量**凭据值（非 `${...}` 占位）。
  这是「真密钥入库」，必须为 0。退出码 1。
* **WARN** —— Java 里 `@Value("${key:default}")` / `getProperty("key","default")` 的
  **credential 类键**带了非空字面量兜底。属**引导态的合理设计**（本地开发要免配置），
  但生产必须由环境变量覆盖，故列为上线前需逐条确认的人工项。不改变退出码。

 本脚本**不打开 `.env`、`.env.example` 或任何凭据文件**，只做源码文本分析，
输出里出现的都是"代码兜底常量"而非线上真实口令，可安全在任意机器 / CI 上跑。

用法
----
    python scripts/check-hardcoded-defaults.py            # 人读
    python scripts/check-hardcoded-defaults.py --json     # 供 CI 消费
    python scripts/check-hardcoded-defaults.py --strict   # WARN 也返回 1（默认只 FAIL 返回 1）
"""

import argparse
import io
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# credential 类键名（用于 Java 侧判定）。刻意用词边界，避免 tokenizer / tokenCount 这类误报。
CRED_KEY = re.compile(
    r"(?:^|[.\-_])(password|passwd|pwd|secret|api[_-]?key|access[_-]?key"
    r"|secret[_-]?key|private[_-]?key|credential|init[_-]?password|default[_-]?password)($|[.\-_])",
    re.IGNORECASE,
)

# 键名虽含 credential 类词，但值语义是"路径/文件名/开关"，不是凭据本身 —— 不计。
NON_SECRET_KEY = re.compile(
    r"(?:^|[.\-_])(file|filename|path|dir|location|url|uri|enabled|enabled?|mode|profile)($|[.\-_])",
    re.IGNORECASE,
)

# credential 类 YAML 键（用于配置侧判定）
CRED_YAML_KEY = re.compile(
    r"^\s*(password|passwd|pwd|secret|api-?key|access-key|secret-key|token|credential)\s*:\s*(.+?)\s*$",
    re.IGNORECASE,
)

# 合法的"非字面量"取值形态
PLACEHOLDER = re.compile(
    r"^(?:\$\{[^}]*\}|null|~|\"\"|''|\"|'|true|false|0|)$",
    re.IGNORECASE,
)

# Java: @Value("${key:default}")  —— group1=key, group2=default(可空)
JAVA_VALUE = re.compile(r'@Value\(\s*"\$\{([^}:]+)(?::([^}]*))?\}"\s*\)')

# Java: getProperty("key", "default") / getProperty("key","default")
JAVA_GETPROP = re.compile(
    r'getProperty\(\s*"([^"]+)"\s*,\s*"([^"]*)"\s*\)'
)


def iter_files():
    """只遍历会进 git 的源码/配置目录（.env* 已被显式排除，不进这个循环）。"""
    for base in sorted(os.listdir(ROOT)):
        full = os.path.join(ROOT, base)
        if not os.path.isdir(full):
            continue
        if not (base.startswith("judge-") or base in ("deploy", "sql", "scripts", "perf-test")):
            continue
        for dirpath, dirnames, filenames in os.walk(full):
            # 跳过构建/依赖/覆盖率产物
            dirnames[:] = [
                d for d in dirnames
                if d not in ("target", "node_modules", "coverage", ".git", "dist", "__pycache__")
            ]
            for fn in filenames:
                if fn.endswith((".java", ".yml", ".yaml", ".properties")):
                    yield os.path.join(dirpath, fn)


def rel(path):
    return os.path.relpath(path, ROOT).replace("\\", "/")


def _is_cred_key(key):
    """键名像凭据，且不是凭据的"附属属性"（路径/开关/URL）。"""
    if NON_SECRET_KEY.search(key):
        return False
    return bool(CRED_KEY.search(key))


def _render(default):
    """把 Java 兜底表达式渲染成人能读的形态（常量引用与字面量要能区分开）。"""
    d = default.strip()
    if "+" in d:
        # @Value("${key:" + FALLBACK_DEFAULT_PASSWORD + "}") 这类拼接，标出被引用的常量
        refs = [r for r in re.findall(r"[A-Za-z_$][\w$]*", d) if r != "String"]
        if refs:
            return "表达式引用 " + " + ".join(refs)
    return d.strip('"').strip() or d


def scan_file(path):
    fails, warns = [], []
    name = rel(path)
    try:
        text = io.open(path, encoding="utf-8", errors="replace").read()
    except OSError:
        return fails, warns

    is_java = name.endswith(".java")
    is_conf = (not is_java) and (
        "/src/main/resources/" in name or name.startswith("deploy/")
    )

    for i, line in enumerate(text.splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith(("#", "//", "*", "<!--")):
            continue  # 注释行不计

        if is_java:
            for m in JAVA_VALUE.finditer(line):
                key, default = m.group(1), (m.group(2) or "").strip()
                if _is_cred_key(key) and default and not PLACEHOLDER.match(default):
                    warns.append({
                        "file": name, "line": i, "kind": "java-fallback-default",
                        "key": key, "value": _render(default),
                    })
            for m in JAVA_GETPROP.finditer(line):
                key, default = m.group(1), m.group(2).strip()
                if _is_cred_key(key) and default and not PLACEHOLDER.match(default):
                    warns.append({
                        "file": name, "line": i, "kind": "java-fallback-default",
                        "key": key, "value": _render(default),
                    })
        elif is_conf:
            m = CRED_YAML_KEY.match(line)
            if not m:
                continue
            value = m.group(2).strip()
            if PLACEHOLDER.match(value):
                continue
            # 排除对其它变量的引用与文档残留
            if value.startswith("${") or value.startswith("$"):
                continue
            fails.append({
                "file": name, "line": i, "kind": "literal-credential-in-config",
                "key": m.group(1), "value": value,
            })
    return fails, warns


def main():
    ap = argparse.ArgumentParser(description="零硬编码静态自检（不读凭据文件）")
    ap.add_argument("--json", action="store_true", help="输出 JSON")
    ap.add_argument("--strict", action="store_true", help="WARN 也计入退出码")
    args = ap.parse_args()

    all_fails, all_warns = [], []
    scanned = 0
    for path in iter_files():
        scanned += 1
        f, w = scan_file(path)
        all_fails += f
        all_warns += w

    result = {
        "scanned_files": scanned,
        "fail": all_fails,
        "warn": all_warns,
        "fail_count": len(all_fails),
        "warn_count": len(all_warns),
    }

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        print(f"扫描文件：{scanned}")
        print()
        print(f"FAIL（配置里出现字面量凭据，必须为 0）：{len(all_fails)}")
        for h in all_fails:
            print(f"  ✗ {h['file']}:{h['line']}  {h['key']} = {h['value']}")
        print()
        print(f"WARN（代码兜底默认值，生产须由环境变量覆盖）：{len(all_warns)}")
        for h in all_warns:
            print(f"  ! {h['file']}:{h['line']}  {h['key']} → 兜底 {h['value']!r}")
        print()
        if not all_fails and not all_warns:
            print("结论：PASS —— 未发现硬编码凭据，也无凭据类兜底默认值。")
        elif not all_fails:
            print("结论：PASS（有 WARN）—— 无密钥入库；但上面每一条 WARN 都要确认"
                  "生产环境已用 CJ_* 环境变量覆盖（见 docs/DEPLOYMENT.md §7.1）。")
        else:
            print("结论：FAIL —— 配置文件里出现字面量凭据，请改为 ${ENV_VAR} 引用。")

    if all_fails or (args.strict and all_warns):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
