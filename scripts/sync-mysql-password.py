#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
MySQL 凭据一致性诊断与修复（rotate-credentials.py 第 ③ 步的落地工具）。

背景（2026-09-22 实测得到的事实链）
---------------------------------
项目硬规则：`MYSQL_ROOT_PASSWORD`（建容器/初始化数据卷用）与 `MYSQL_PASSWORD`
（judge-*/application.yml:39 `password: ${MYSQL_PASSWORD}`，服务真正用来连库的）
**必须同值**。rotate-credentials.py 对两者还各要求 ≥24 字符。

只改 .env 不改数据卷 → 数据卷里的 root 口令没变 → 服务报
    Access denied for user 'root'@'172.19.0.1' (using password: YES)
容器 healthcheck 用 `mysqladmin ping`，**对密码不敏感**，所以容器始终 healthy，
足以骗过"容器在跑 = 环境正常"的判断（docs/CONTEXT.md §5.6 记录过）。

两种不通法，对应两种修法（脚本会自己判断该用哪种）
------------------------------------------------
  A. 数据卷 != .env          → `--sync-db`：ALTER root@% = .env 的值（= rotate 脚本第 224-226 行）
  B. .env 内部两键不同值      → `--align-app-key`：把 MYSQL_PASSWORD 改成与 MYSQL_ROOT_PASSWORD 同值
     （2026-09-22 本机实际就是这种：MYSQL_PASSWORD 还是模板遗留的 6 位弱默认值，
       而数据卷早已是 MYSQL_ROOT_PASSWORD 的值 —— 此时 ALTER 是空操作，改 .env 才对。）

安全约束
--------
* 全程**不打印任何明文口令** —— 输出只有长度、是否一致、布尔命中、SQL 是否成功；
* 口令通过 `docker exec` 的 **stdin** 传入容器，不出现在命令行参数里（ps / docker inspect 看不到）；
* 改 .env 只替换目标键那一行，其余字节（含 CRLF）原样保留，并先留 `.env.bak`（已被 .gitignore 覆盖）；
* 明文只在容器内 /tmp 的临时 client 配置里短暂存在，用完立即删除。

用法
----
    python scripts/sync-mysql-password.py --check          # 只诊断，不改任何东西
    python scripts/sync-mysql-password.py --align-app-key  # 修 .env：MYSQL_PASSWORD ← MYSQL_ROOT_PASSWORD
    python scripts/sync-mysql-password.py --sync-db        # 修数据卷：ALTER root@% = .env 的值
"""

import argparse
import base64
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_FILE = os.path.join(ROOT, ".env")
CONTAINER = "codejudge-mysql"
APP_KEY = "MYSQL_PASSWORD"          # 服务连库实际读的那个（judge-*/application.yml:39）
ROOT_KEY = "MYSQL_ROOT_PASSWORD"    # 建容器时注入、且是数据卷当前值
TMP = "/tmp/.cjsync"
_REMOTE_CACHE = []


# --------------------------------------------------------------------------- 读与投递
def read_env(path, keys=None):
    """只取指定键（keys=None 取全部）；**绝不打印**。"""
    found = {}
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            s = line.strip()
            if not s or s.startswith("#") or "=" not in s:
                continue
            key, _, val = s.partition("=")
            key = key.strip()
            if keys is None or key in keys:
                found[key] = val.strip().strip('"').strip("'")
    return found


def container_pw():
    """容器 env 里的 MYSQL_ROOT_PASSWORD（= 数据卷 root@localhost 的口令，用于认证）。"""
    proc = subprocess.run(
        ["docker", "exec", CONTAINER, "sh", "-c", 'printf %s "$MYSQL_ROOT_PASSWORD"'],
        capture_output=True,
    )
    if proc.returncode != 0:
        sys.exit(f"[x] 无法读取容器 env：{proc.stderr.decode('utf-8', 'replace').strip()}")
    return proc.stdout.decode("utf-8", "replace")


def remote_target():
    """返回能让 mysql 客户端命中 **root@%** 的 -h/-P 参数。

    账号匹配取决于来源地址：不加 -h 走 unix socket → root@localhost；
    应用从容器网络连进来命中的是 root@%，所以"服务能否连上"必须按 root@% 判。

    为什么用宿主侧 `docker inspect` 而不是容器内命令：
      * mysql:8.0 镜像**没有 hostname** 命令；
      * 容器内 `/etc/hosts` 这类路径会被 Git Bash 的 MSYS 路径转换吃掉（实测）。
    """
    if _REMOTE_CACHE:
        return _REMOTE_CACHE[0]
    proc = subprocess.run(
        ["docker", "inspect", "-f",
         "{{range .NetworkSettings.Networks}}{{.IPAddress}} {{end}}", CONTAINER],
        capture_output=True,
    )
    ips = proc.stdout.decode("utf-8", "replace").split()
    if not ips:
        sys.exit("[x] 取不到容器 IP，无法构造 root@% 探针；请确认容器在运行。")
    target = f"-h {ips[0]}"
    _REMOTE_CACHE.append(target)
    return target


def run_in_container(script):
    """把 sh 脚本经 **stdin** 投给容器执行（脚本里只出现 base64，不出现明文）。"""
    proc = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "sh", "-s"],
        input=script.encode("utf-8"),
        capture_output=True,
    )
    return (proc.returncode,
            proc.stdout.decode("utf-8", "replace").strip(),
            proc.stderr.decode("utf-8", "replace").strip())


def scrub(text, secrets):
    for secret in secrets:
        if secret:
            text = text.replace(secret, "***")
    return text


def first_error(stderr):
    """取第一行**真正的**报错：跳过 mysql 自己的 [Warning] 行。

    （挂载的 my.cnf 是 world-writable，mysql 每次都会先吐一行 Warning，
      它会把真正的 'Access denied' 挤到后面 —— 只看首行会误判。）
    """
    for line in stderr.splitlines():
        line = line.strip()
        if line and "[Warning]" not in line:
            return [line]
    return []


def b64(text):
    return base64.b64encode(text.encode("utf-8")).decode("ascii")


def cnf(password):
    """MySQL option file；值用双引号包裹并转义 \\ 与 " 。"""
    escaped = password.replace("\\", "\\\\").replace('"', '\\"')
    return '[client]\nuser=root\npassword="%s"\n' % escaped


def sql_alter(password):
    escaped = password.replace("\\", "\\\\").replace("'", "''")
    return "ALTER USER 'root'@'%' IDENTIFIED BY '%s'; FLUSH PRIVILEGES;\n" % escaped


# --------------------------------------------------------------------------- 动作
def probe(password, secrets, remote=False):
    """用给定口令连一次库，返回 (ok, 首行错误)。remote=True 走 TCP，命中 root@%。

    `--defaults-extra-file` **必须是 mysql 命令行的第一个选项**，
    否则 mysql 会报 `unknown variable 'defaults-extra-file=...'`（实测踩到），
    所以 -h 只能排在它后面。
    """
    host = remote_target() + " " if remote else ""
    script = (
        "set -e\n"
        "printf %s '{cnf}' | base64 -d > {tmp}.cnf\n"
        "chmod 600 {tmp}.cnf\n"
        "mysql --defaults-extra-file={tmp}.cnf {host}-N -e 'SELECT 1'\n"
        "rm -f {tmp}.cnf\n"
    ).format(cnf=b64(cnf(password)), tmp=TMP, host=host)
    code, out, err = run_in_container(script)
    return code == 0 and out.strip().startswith("1"), first_error(scrub(err, secrets))


def sync_db(old, new, secrets):
    """认证用 old（socket → root@localhost），把 root@% 改成 new，再从 TCP 复验。"""
    script = (
        "set -e\n"
        "printf %s '{cnf_old}' | base64 -d > {tmp}-old.cnf\n"
        "chmod 600 {tmp}-old.cnf\n"
        "printf %s '{sql}' | base64 -d > {tmp}.sql\n"
        "mysql --defaults-extra-file={tmp}-old.cnf < {tmp}.sql\n"
        "printf %s '{cnf_new}' | base64 -d > {tmp}-new.cnf\n"
        "chmod 600 {tmp}-new.cnf\n"
        "mysql --defaults-extra-file={tmp}-new.cnf {host}-N -e 'SELECT 1'\n"
        "rm -f {tmp}-old.cnf {tmp}-new.cnf {tmp}.sql\n"
    ).format(cnf_old=b64(cnf(old)), cnf_new=b64(cnf(new)), sql=b64(sql_alter(new)),
             tmp=TMP, host=remote_target() + " ")
    code, out, err = run_in_container(script)
    return code == 0 and out.strip().startswith("1"), first_error(scrub(err, secrets))


def align_app_key(path, keys):
    """把 MYSQL_PASSWORD 改成与 MYSQL_ROOT_PASSWORD 同值：只替换那一行，其余字节原样保留。"""
    with open(path, "r", encoding="utf-8", newline="") as fh:
        raw = fh.read()
    lines = raw.splitlines(keepends=True)
    index = None
    for idx, line in enumerate(lines):
        s = line.strip()
        if s.startswith("#") or "=" not in s:
            continue
        if s.partition("=")[0].strip() == APP_KEY:
            index = idx
            break
    if index is None:
        sys.exit(f"[x] .env 里找不到 {APP_KEY} 行")

    body, _, rest = lines[index].partition("=")
    ending = ""
    for candidate in ("\r\n", "\n"):
        if rest.endswith(candidate):
            ending, rest = candidate, rest[:-len(candidate)]
            break
    backup = path + ".bak"
    shutil.copyfile(path, backup)
    lines[index] = f"{body}={keys[ROOT_KEY]}{ending}"
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write("".join(lines))
    changed = "".join(lines) != raw
    return index + 1, len(rest), len(keys[ROOT_KEY]), backup, changed


# --------------------------------------------------------------------------- 主流程
def main():
    parser = argparse.ArgumentParser(description="MySQL 凭据一致性诊断与修复")
    parser.add_argument("--check", action="store_true", help="只诊断，不改任何东西（默认）")
    parser.add_argument("--align-app-key", action="store_true",
                        help="修 .env：MYSQL_PASSWORD ← MYSQL_ROOT_PASSWORD（先存 .env.bak）")
    parser.add_argument("--sync-db", action="store_true",
                        help="修数据卷：ALTER root@%% = .env 的 MYSQL_PASSWORD 值")
    args = parser.parse_args()
    if not (args.align_app_key or args.sync_db):
        args.check = True

    if not os.path.exists(ENV_FILE):
        sys.exit(f"[x] 找不到 {ENV_FILE}")
    keys = read_env(ENV_FILE, {APP_KEY, ROOT_KEY})
    app_pw, root_pw = keys.get(APP_KEY, ""), keys.get(ROOT_KEY, "")
    if not app_pw or not root_pw:
        sys.exit(f"[x] .env 缺少 {APP_KEY} / {ROOT_KEY}，读取到：{sorted(keys)}")
    secrets = [app_pw, root_pw]

    print("== 诊断 ==")
    same = app_pw == root_pw
    print(f"  .env  {APP_KEY:<21} 长度={len(app_pw)}")
    print(f"  .env  {ROOT_KEY:<21} 长度={len(root_pw)}   两者一致={'是' if same else '否 ← 违反项目硬规则'}")
    old_env = container_pw()
    print(f"  容器 env {ROOT_KEY:<15} 长度={len(old_env)}（建容器时注入；= 数据卷 root 口令）")

    print("  账号探针（应用连库这条路走的是 root@%）：")
    cases = (
        (f"{APP_KEY} → root@%", app_pw, True),
        (f"{APP_KEY} → root@localhost", app_pw, False),
        (f"{ROOT_KEY} → root@%", root_pw, True),
        (f"{ROOT_KEY} → root@localhost", root_pw, False),
    )
    hits = {}
    for label, pw, remote in cases:
        ok, err = probe(pw, secrets, remote=remote)
        hits[(pw is app_pw, remote)] = ok
        note = f"   （{err[0]}）" if (err and not ok) else ""
        print(f"    [{'命中' if ok else '失败'}] {label}{note}")

    app_ok = hits.get((True, True), False)
    root_on_pct = hits.get((False, True), False)
    print()
    if app_ok:
        print(f"[√] root@% 已等于 {APP_KEY}，服务可以连库。")
        return 0

    # 定性：应用连不上，是"数据卷旧"还是".env 两键不一致"？
    if root_on_pct:
        print(f"[!] 定性：root@% 已等于 {ROOT_KEY} —— 数据卷没问题，是 **.env 内部两键不一致**（场景 B）。")
        print("    此时 ALTER 是空操作；正确修法是让 MYSQL_PASSWORD = MYSQL_ROOT_PASSWORD。")
        if args.align_app_key:
            line_no, old_len, new_len, backup, changed = align_app_key(ENV_FILE, keys)
            print(f"\n== 修 .env ==\n  第 {line_no} 行 {APP_KEY}：长度 {old_len} → {new_len}"
                  f"（新值 = {ROOT_KEY}，不打印内容）")
            print(f"  备份：{os.path.relpath(backup, ROOT)}（已被 .gitignore 覆盖，不会进 git）")
            print(f"  文件是否真的变化：{'是' if changed else '否 —— 内容已一致'}")
            ok, err = probe(read_env(ENV_FILE, {APP_KEY})[APP_KEY], secrets, remote=True)
            print(f"  复验 root@%：{'通过' if ok else '失败'}")
            return 0 if ok else 1
        print("\n[i] --check 模式，未做修改。加 --align-app-key 即执行修复。")
        return 0

    print(f"[!] 定性：root@% 既不等于 {APP_KEY} 也不等于 {ROOT_KEY}（场景 A，数据卷被动过）。")
    if args.sync_db:
        print("\n== 修数据卷 ==\n  以容器 env 旧口令认证 root@localhost，把 root@% 改成 .env 的 "
              f"{APP_KEY} 值（长度 {len(app_pw)}）")
        ok, err = sync_db(old_env, app_pw, secrets)
        if ok:
            print("[√] ALTER 成功，且已用新口令从 TCP 复验 root@% 通过。")
            print("    root@localhost 未改动 —— 与 rotate-credentials.py 第 224-226 行规定一致。")
            return 0
        print("[x] 同步失败。")
        if err:
            print(f"    {err[0]}")
        return 1
    print("\n[i] --check 模式，未做修改。加 --sync-db 即执行修复。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
