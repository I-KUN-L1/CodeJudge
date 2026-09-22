#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""凭据轮换与强度审计 —— CodeJudge 上线前必做项（`docs/DEPLOYMENT.md` §7 第 1 条）。

用法：
    python scripts/rotate-credentials.py                     # 只读审计（默认，不改任何文件）
    python scripts/rotate-credentials.py --rotate            # 生成新值但只打印（dry-run）
    python scripts/rotate-credentials.py --rotate --write    # 真正回写 .env（自动备份为 .env.bak.<时间戳>）

⚠ 三条必须理解的限制（否则轮换会直接把服务打挂）：

  1. **改 `.env` ≠ 改数据卷里的密码。**
     MySQL / PostgreSQL / MinIO 的密码**存在数据卷里**，`.env` 只是「下次连接时用哪个密码」。
     只改 `.env` 不改数据库 → 服务全线连不上（表现为启动即失败或所有查询 500）。
     脚本在 `--rotate` 时会打印对应的**同步命令**，必须一并执行。

  2. `.env` 是 **properties 格式**（各服务 `spring.config.import: optional:file:./.env[.properties]`
     直接读），**值不能加引号**。因此生成的随机串只含字母数字，
     不含 `$ # = 空格 " ' \\` 等会破坏解析或需要转义的字符。

  3. 轮换 `CJ_JWT_SECRET` 会让**所有已签发的 access/refresh token 立即失效**，
     全站用户（含管理员）需重新登录。这是预期行为 —— 请在维护窗口执行。

退出码：0 = 审计通过 / 执行成功；1 = 发现弱凭据或缺失项（需处理）；2 = 环境错误。
"""
from __future__ import annotations

import argparse
import datetime as dt
import pathlib
import re
import secrets
import shutil
import string
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
ENV_PATH = ROOT / ".env"
EXAMPLE_PATH = ROOT / ".env.example"

ALNUM = string.ascii_letters + string.digits

# 弱凭据特征：大小写不敏感的子串匹配。命中即判定为「未改」。
WEAK_PATTERNS = [
    "your-", "your_", "change-me", "changeme", "placeholder", "example", "todo",
    "123456", "123123", "000000", "111111", "abc123", "qwerty", "letmein",
    "password", "passwd", "secret", "admin", "root", "codejudge", "test",
]

# 敏感项定义：(键名, 最小长度, 类别, 说明)
#   auto     = 可自动生成，直接轮换
#   manual   = 可生成，但改了要人工分发（初始密码类），默认跳过，需 --include-init-passwords
#   external = 外部签发，脚本不能生成（只审计、提示人工更换）
SENSITIVE = [
    ("MYSQL_ROOT_PASSWORD",       24, "auto",     "MySQL root 密码（容器初始化 + 服务连接共用）"),
    ("MYSQL_PASSWORD",            24, "auto",     "MySQL 业务连接密码（MYSQL_USERNAME=root，须与 root 同值）"),
    ("REDIS_PASSWORD",            24, "auto",     "Redis requirepass（容器启动参数注入，重建即生效）"),
    ("POSTGRES_PASSWORD",         24, "auto",     "PostgreSQL 密码（存 pgdata 数据卷，须 ALTER USER）"),
    ("MINIO_ROOT_PASSWORD",       24, "auto",     "MinIO root 密码（环境变量注入，重建即生效）"),
    ("CJ_JWT_SECRET",             48, "auto",     "JWT 签名密钥（HS384 ≥48 字节；轮换踢掉全部在线会话）"),
    ("GRAFANA_ADMIN_PASSWORD",    16, "auto",     "Grafana 管理员密码（⚠ 当前 .env 未定义，走 compose 默认值 codejudge）"),
    ("CJ_ADMIN_INIT_PASSWORD",    12, "manual",   "首个管理员初始密码（首次登录强制改密）"),
    ("CJ_USER_DEFAULT_PASSWORD",  12, "manual",   "管理员重置用户后的统一初始密码"),
    ("CJ_LLM_API_KEY",            20, "external", "LLM API Key（在服务商控制台轮换后回填，脚本无法生成）"),
]

# 用户名类：不是密钥，但可猜的用户名会降低爆破成本（低风险，仅提示）
WEAK_USERNAMES = [
    ("MYSQL_USERNAME", "root", "数据库用 root 连接：最小权限原则下建议建独立业务账号"),
    ("CJ_ADMIN_USERNAME", "admin", "首个管理员用户名：可猜，建议换成非字典名"),
    ("MINIO_ROOT_USER", "codejudge", "MinIO 用户名：可猜，建议更换"),
]


# ----------------------------------------------------------------- 工具

def read_env(path: pathlib.Path) -> str:
    """按原样读取（保留 CRLF），不存在则报错退出。"""
    if not path.exists():
        print(f"[FATAL] 找不到 {path}", file=sys.stderr)
        sys.exit(2)
    with open(path, "r", encoding="utf-8", newline="") as f:
        return f.read()


def parse_env(text: str) -> dict[str, str]:
    """只解析 KEY=VALUE 行（忽略注释与空行）。"""
    out: dict[str, str] = {}
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or "=" not in s:
            continue
        k, _, v = s.partition("=")
        out[k.strip()] = v
    return out


def gen_secret(n: int) -> str:
    """生成 n 位字母数字随机串，保证大小写与数字各至少一个。

    刻意不用 base64/urlsafe：`spring.config.import` 读的是 `.properties` 格式，
    含 `$ # = \\` 等字符会破坏解析（properties 里未转义的 `\\` 会被吃掉）。
    """
    while True:
        s = "".join(secrets.choice(ALNUM) for _ in range(n))
        if (any(c.islower() for c in s) and any(c.isupper() for c in s)
                and any(c.isdigit() for c in s)):
            return s


def verdict(value: str, min_len: int) -> tuple[str, str]:
    """返回 (状态, 原因)。状态：OK / WEAK / EMPTY / MISSING。"""
    if value is None:
        return "MISSING", "未在 .env 中定义"
    if not value.strip():
        return "EMPTY", "值为空"
    low = value.lower()
    for pat in WEAK_PATTERNS:
        if pat in low:
            return "WEAK", f"命中弱凭据特征「{pat}」"
    if len(value) < min_len:
        return "WEAK", f"长度 {len(value)} < 要求的 {min_len}"
    return "OK", f"长度 {len(value)}"


# ----------------------------------------------------------------- 审计

def do_audit(env: dict[str, str], *, quiet: bool = False) -> int:
    print("=" * 74)
    print("凭据强度审计")
    print("=" * 74)
    print(f"{'键':<26} {'长度':>4}  {'状态':<8} 说明")
    print("-" * 74)

    problems: list[str] = []
    for key, min_len, kind, note in SENSITIVE:
        val = env.get(key)
        state, why = verdict(val, min_len)
        if kind == "external" and state == "EMPTY":
            state = "MANUAL"          # 外部签发的 key 留空是合法的（LLM 未启用）
        mark = {"OK": "OK", "WEAK": "WEAK", "EMPTY": "EMPTY",
                "MISSING": "MISSING", "MANUAL": "MANUAL"}[state]
        length = len(val) if val is not None else 0
        print(f"{key:<26} {length:>4}  {mark:<8} {note}")
        if state != "OK":
            print(f"{'':<26} {'':>4}  {'':<8} → {why}")
        if state in ("WEAK", "EMPTY", "MISSING"):
            problems.append(f"{key}: {why}")

    # 用户名风险（低）
    print("-" * 74)
    for key, bad, note in WEAK_USERNAMES:
        cur = env.get(key, "")
        flag = "WARN" if cur == bad else "OK"
        print(f"{key:<26} {len(cur):>4}  {flag:<8} {note}")
        if flag == "WARN":
            print(f"{'':<26} {'':>4}  {'':<8} → 当前值可猜（不阻塞上线，建议一并更换）")

    print("-" * 74)
    if problems:
        print(f"发现 {len(problems)} 项需要处理：")
        for p in problems:
            print(f"  · {p}")
    else:
        print("全部敏感项强度达标。")

    if not quiet:
        print()
        print("⚠ 提醒：审计只检查 `.env` 中的值。若数据卷里的密码仍是旧值，")
        print("   改 .env 后服务将连不上 —— 轮换请用 `--rotate --write` 并执行其打印的同步命令。")
    return 1 if problems else 0


# ----------------------------------------------------------------- 轮换

def build_replacements(env: dict[str, str], *, include_init: bool) -> dict[str, str]:
    """决定每个键的新值。返回 {key: new_value}。"""
    new: dict[str, str] = {}
    for key, min_len, kind, _note in SENSITIVE:
        if kind == "external":
            continue                       # 外部签发的不能生成
        if kind == "manual" and not include_init:
            continue                       # 初始密码改了要人工分发，默认不动
        gen_len = max(min_len + 8, 32) if key == "CJ_JWT_SECRET" else max(min_len + 4, 20)
        new[key] = gen_secret(gen_len)
    return new


def apply_replacements(text: str, repl: dict[str, str]) -> tuple[str, list[str], list[str]]:
    """按行替换，保持 CRLF 与注释/顺序不变。返回 (新文本, 已替换键, 新增键)。"""
    replaced: list[str] = []
    appended: list[str] = []
    remaining = dict(repl)

    lines = text.splitlines(keepends=True)
    out: list[str] = []
    for line in lines:
        stripped = line.rstrip("\r\n")
        eol = line[len(stripped):] or "\n"
        m = re.match(r"^(\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*)=", stripped)
        if m and m.group(2) in remaining:
            key = m.group(2)
            out.append(f"{m.group(1)}{key}={remaining.pop(key)}{eol}")
            replaced.append(key)
        else:
            out.append(line)

    for key, val in remaining.items():
        if not out or not out[-1].endswith(("\n", "\r")):
            out.append("\r\n")
        out.append(f"{key}={val}\r\n")
        appended.append(key)

    return "".join(out), replaced, appended


SYNC_HINTS = r"""
======================================================================
⚠ 同步命令：只改 .env 不够，必须让数据卷里的密码一起改
======================================================================
下面命令里的 <OLD_*> 指**轮换前**的值（备份文件 .env.bak.<时间戳> 里可查）。
按顺序执行，全部完成后再重启服务：

① MySQL（密码存在数据卷，必须 ALTER USER）
   docker exec -i codejudge-mysql mysql -uroot -p'<OLD_MYSQL_ROOT_PASSWORD>' -e \
     "ALTER USER 'root'@'%' IDENTIFIED BY '<NEW_MYSQL_ROOT_PASSWORD>'; FLUSH PRIVILEGES;"
   ℹ .env 里 MYSQL_USERNAME=root，故 MYSQL_PASSWORD 与 MYSQL_ROOT_PASSWORD 必须同值。

② PostgreSQL（密码存在 pgdata 数据卷）
   docker exec -i codejudge-pg psql -U postgres -d judge_ai -v ON_ERROR_STOP=1 -c \
     "ALTER USER postgres WITH PASSWORD '<NEW_POSTGRES_PASSWORD>';"

③ Redis（密码由 compose 的 --requirepass 注入，重建容器即生效）
   cd <项目根> && docker-compose up -d --force-recreate redis

④ MinIO（密码由环境变量注入，重建容器即生效）
   cd <项目根> && docker-compose up -d --force-recreate minio

⑤ Grafana（密码由环境变量注入，重建容器即生效）
   cd deploy/monitoring && docker-compose -f docker-compose.monitoring.yml up -d --force-recreate grafana

⑥ 重启 8 个后端服务，让它们读新的 .env：
   python scripts/dev-start-backend.py --wait

⑦ 验证：
   python scripts/preflight-check.py        # 凭据强度与配置自检
   python scripts/verify-p6.py              # 端到端回归
"""


def do_rotate(env: dict[str, str], text: str, *, write: bool, include_init: bool) -> int:
    repl = build_replacements(env, include_init=include_init)
    if not repl:
        print("没有可自动轮换的项（是否漏了 --include-init-passwords？）")
        return 1

    new_text, replaced, appended = apply_replacements(text, repl)

    print("=" * 74)
    print(f"凭据轮换 —— {'已回写 .env' if write else '预演（未落盘）'}")
    print("=" * 74)
    print(f"{'键':<26} {'旧长度':>6} {'新长度':>6}  动作")
    print("-" * 74)
    for key, val in repl.items():
        old_len = len(env.get(key, ""))
        action = "替换" if key in replaced else "新增"
        print(f"{key:<26} {old_len:>6} {len(val):>6}  {action}")
    print("-" * 74)
    print(f"共 {len(replaced)} 项替换、{len(appended)} 项新增。")

    if "GRAFANA_ADMIN_PASSWORD" in appended:
        print()
        print("ℹ GRAFANA_ADMIN_PASSWORD 已追加到 .env —— 此前它只存在于")
        print("  deploy/monitoring/docker-compose.monitoring.yml 的默认值（codejudge）中。")

    if write:
        stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
        backup = ENV_PATH.with_name(f".env.bak.{stamp}")
        shutil.copy2(ENV_PATH, backup)
        with open(ENV_PATH, "w", encoding="utf-8", newline="") as f:
            f.write(new_text)
        print()
        print(f"✅ 已回写 {ENV_PATH}")
        print(f"   备份：{backup}（**含旧值，勿提交，勿长期留存**）")
    else:
        print()
        print("（预演模式，未改动任何文件。确认无误后加 --write 落盘。）")
        print()
        print("新值预览（前 6 位）：")
        for key, val in repl.items():
            print(f"  {key:<26} {val[:6]}…")

    if include_init:
        print()
        print("⚠ 已包含初始密码（CJ_ADMIN_INIT_PASSWORD / CJ_USER_DEFAULT_PASSWORD）：")
        print("  改强随机后，新建用户与管理员重置密码后拿到的初始密码即新值，")
        print("  必须通过安全渠道告知使用者，否则他们将无法登录。")

    if "CJ_JWT_SECRET" in repl:
        print()
        print("⚠ CJ_JWT_SECRET 已轮换：**所有已签发的 token 立即失效**，全站需重新登录。")

    print(SYNC_HINTS)
    return 0


# ----------------------------------------------------------------- 入口

def main() -> int:
    ap = argparse.ArgumentParser(
        description="CodeJudge 凭据轮换与强度审计",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__)
    ap.add_argument("--rotate", action="store_true",
                    help="生成新随机值（默认仅打印，配合 --write 才落盘）")
    ap.add_argument("--write", action="store_true",
                    help="真正回写 .env（自动备份为 .env.bak.<时间戳>）")
    ap.add_argument("--include-init-passwords", action="store_true",
                    help="连初始密码（CJ_ADMIN_INIT_PASSWORD 等）一起轮换 —— 需人工分发")
    ap.add_argument("--audit", action="store_true",
                    help="显式执行只读审计（默认行为）")
    args = ap.parse_args()

    text = read_env(ENV_PATH)
    env = parse_env(text)

    if args.write and not args.rotate:
        print("[ERROR] --write 必须与 --rotate 一起用（防误操作）。", file=sys.stderr)
        return 2

    if args.rotate:
        return do_rotate(env, text, write=args.write,
                         include_init=args.include_init_passwords)

    rc = do_audit(env)
    if rc:
        print()
        print("修复方式：python scripts/rotate-credentials.py --rotate        # 先预演")
        print("          python scripts/rotate-credentials.py --rotate --write # 确认后落盘")
    return rc


if __name__ == "__main__":
    sys.exit(main())
