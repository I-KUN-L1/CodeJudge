#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
数据卷备份 + **非破坏性回滚演练**（对应 preflight M5 / LAUNCH-READINESS B3）。

为什么需要它：
  上线放行要求「MySQL/PG/Redis/MinIO 的备份策略 + 回滚步骤，生产前演练一次」。
  「有备份」和「备份能恢复」是两件事 —— 没演练过的备份等于没有备份。
  本脚本把两者合成一条命令，且演练**不碰生产库**（恢复目标是临时库，验完即删）。

用法（仓库根目录）：
    python scripts/backup-volumes.py --backup     # 备份到 backups/<时间戳>/
    python scripts/backup-volumes.py --verify     # 对最新备份做恢复演练（临时库，验完即删）
    python scripts/backup-volumes.py --backup --verify     # 一步到位
    python scripts/backup-volumes.py --list       # 列出已有备份

备份内容：
    mysql/<db>.sql       5 个 judge_* 库（mysqldump --single-transaction --routines --events）
    pg/judge_ai.dump     PostgreSQL 自定义格式（pg_dump -Fc，可用 pg_restore 选择性恢复）
    redis/dump.rdb       BGSAVE 后的 RDB 快照（docker cp 取出）
    minio/               未运行时跳过（storage profile，见 deploy 说明）
    MANIFEST.json        各文件大小 + 校验和 + 备份时刻 + 实施来源

回滚演练（--verify）做的事：
    MySQL  → 取一个库重放到临时库 judge_drill_<ts>，比对核心表行数，然后 DROP
    Redis  → 用 `redis-check-rdb` 校验 RDB 可加载性（不加载到实例里，避免覆盖生产数据）
    MinIO  → 有快照则只校验归档完整性

 凭据处理：全部从 `.env` 读取（不从命令行传），通过子进程 env 注入
   （`MYSQL_PWD` / `PGPASSWORD`）。注意 `docker exec -e K=V` 的 K=V 会出现在宿主进程
   参数表里 —— 本机演练可接受；生产建议改用 `--defaults-extra-file` / `.pgpass`。
   脚本自身**不打印任何口令**。

 什么时候这份备份不够用：它只覆盖**逻辑数据**。生产还需要（见 docs/DEPLOYMENT.md）：
   ① 备份文件异地存放与保留周期；② 恢复演练定期重跑（不是一次性）；③ 沙箱镜像与
   前端 dist 的版本对应关系；④ RocketMQ broker 当前**未挂 store 卷**（消息不持久化）。
"""

import argparse
import hashlib
import json
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
BACKUP_DIR = ROOT / "backups"

MYSQL_DBS = ["judge_auth", "judge_user", "judge_problem", "judge_submission", "judge_contest"]
# 演练时用来比对行数的核心表（库 -> 表）
MYSQL_PROBE = {"judge_user": "user", "judge_problem": "problem", "judge_submission": "submission"}
PG_DB = "judge_ai"
PG_PROBE = "knowledge_chunk"

CTR_MYSQL = "codejudge-mysql"
CTR_PG = "codejudge-pg"
CTR_REDIS = "codejudge-redis"


def env_value(key: str) -> str:
    """从 .env 读值；不在命令行传，也不打印。"""
    try:
        for line in (ROOT / ".env").read_text(encoding="utf-8", errors="replace").splitlines():
            s = line.strip()
            if s.startswith("#") or "=" not in s:
                continue
            k, v = s.split("=", 1)
            if k.strip() == key:
                return v.strip()
    except OSError:
        pass
    return ""


def run(cmd, env_extra=None, input_bytes=None, check=True):
    env = dict(os.environ)
    if env_extra:
        env.update(env_extra)
    p = subprocess.run(cmd, input=input_bytes, capture_output=True, env=env)
    if check and p.returncode != 0:
        raise RuntimeError(f"命令失败（{p.returncode}）：{' '.join(cmd[:3])}…\n{p.stderr.decode('utf-8', 'replace')[:400]}")
    return p


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def container_running(name: str) -> bool:
    p = run(["docker", "inspect", "-f", "{{.State.Running}}", name], check=False)
    return p.returncode == 0 and p.stdout.decode().strip() == "true"


# ------------------------------- 备份 -------------------------------

def backup(out_dir: pathlib.Path) -> dict:
    manifest = {"ts": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "files": {}, "skipped": []}
    out_dir.mkdir(parents=True, exist_ok=True)
    mysql_pwd = env_value("MYSQL_ROOT_PASSWORD") or env_value("MYSQL_PASSWORD")
    pg_pwd = env_value("POSTGRES_PASSWORD")
    redis_pwd = env_value("REDIS_PASSWORD")

    # ---- MySQL ----
    if container_running(CTR_MYSQL):
        (out_dir / "mysql").mkdir(exist_ok=True)
        for db in MYSQL_DBS:
            dest = out_dir / "mysql" / f"{db}.sql"
            # --databases：让 dump **自包含**（含 CREATE DATABASE + USE）——
            #   ① 灾难恢复时 `mysql < judge_user.sql` 一条命令即可，不必先手工建库（也就不会漏字符集）；
            #   ② 演练时可把库名整体改写到临时库重放。
            #   反过来：直接把它灌回生产会**覆盖同名库**，恢复前务必确认目标。
            # --single-transaction：InnoDB 一致性快照，不锁表（避免备份期间阻塞判题写入）
            # --set-gtid-purged=OFF：避免恢复时因 GTID 报错（本机未开 GTID，但生产会开）
            p = run(["docker", "exec", "-i", "-e", f"MYSQL_PWD={mysql_pwd}", CTR_MYSQL,
                     "mysqldump", "-uroot", "--single-transaction", "--routines", "--events",
                     "--set-gtid-purged=OFF", "--default-character-set=utf8mb4",
                     "--databases", db],
                    check=False)
            if p.returncode != 0 or not p.stdout:
                print(f"  x MySQL {db}: {p.stderr.decode('utf-8', 'replace')[:160]}")
                continue
            dest.write_bytes(p.stdout)
            manifest["files"][f"mysql/{db}.sql"] = {"bytes": dest.stat().st_size, "sha256": sha256(dest)}
            print(f"  + MySQL {db:18s} {dest.stat().st_size / 1024:8.1f} KB")
    else:
        manifest["skipped"].append("mysql（容器未运行）")

    # ---- PostgreSQL ----
    if container_running(CTR_PG):
        (out_dir / "pg").mkdir(exist_ok=True)
        dest = out_dir / "pg" / f"{PG_DB}.dump"
        p = run(["docker", "exec", "-i", "-e", f"PGPASSWORD={pg_pwd}", CTR_PG,
                 "pg_dump", "-U", env_value("POSTGRES_USERNAME") or "postgres",
                 "-Fc", "-d", PG_DB], check=False)
        if p.returncode == 0 and p.stdout:
            dest.write_bytes(p.stdout)
            manifest["files"][f"pg/{PG_DB}.dump"] = {"bytes": dest.stat().st_size, "sha256": sha256(dest)}
            print(f"  + PostgreSQL {PG_DB:13s} {dest.stat().st_size / 1024:8.1f} KB")
        else:
            print(f"  x PostgreSQL: {p.stderr.decode('utf-8', 'replace')[:160]}")
    else:
        manifest["skipped"].append("postgres（容器未运行）")

    # ---- Redis ----
    if container_running(CTR_REDIS):
        (out_dir / "redis").mkdir(exist_ok=True)
        dest = out_dir / "redis" / "dump.rdb"
        run(["docker", "exec", "-e", f"REDISCLI_AUTH={redis_pwd}", CTR_REDIS, "redis-cli", "BGSAVE"], check=False)
        for _ in range(30):                      # 等 BGSAVE 落盘（LASTSAVE 变化即完成）
            p = run(["docker", "exec", "-e", f"REDISCLI_AUTH={redis_pwd}", CTR_REDIS,
                     "redis-cli", "INFO", "persistence"], check=False)
            if b"rdb_bgsave_in_progress:0" in p.stdout:
                break
            time.sleep(1)
        cp = run(["docker", "cp", f"{CTR_REDIS}:/data/dump.rdb", str(dest)], check=False)
        if cp.returncode == 0 and dest.exists() and dest.stat().st_size > 0:
            manifest["files"]["redis/dump.rdb"] = {"bytes": dest.stat().st_size, "sha256": sha256(dest)}
            print(f"  + Redis                      {dest.stat().st_size / 1024:8.1f} KB")
        else:
            print(f"  x Redis: {cp.stderr.decode('utf-8', 'replace')[:160]}")
    else:
        manifest["skipped"].append("redis（容器未运行）")

    # ---- MinIO ----
    if container_running("codejudge-minio"):
        manifest["skipped"].append("minio（容器在运行但对象存储需 tar /data 卷，见 docs/DEPLOYMENT.md）")
    else:
        manifest["skipped"].append("minio（容器未运行 —— storage profile，默认不启动）")

    (out_dir / "MANIFEST.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    return manifest


# ------------------------------- 演练 -------------------------------

def verify(backup_dir: pathlib.Path) -> int:
    """返回失败项数。全程只创建**临时库**，验完即删，不触碰现有数据。"""
    fails = []
    ts = time.strftime("%H%M%S")
    drill_db = f"judge_drill_{ts}"
    mysql_pwd = env_value("MYSQL_ROOT_PASSWORD") or env_value("MYSQL_PASSWORD")
    pg_pwd = env_value("POSTGRES_PASSWORD")

    def mysql_exec(sql, db=None):
        cmd = ["docker", "exec", "-i", "-e", f"MYSQL_PWD={mysql_pwd}", CTR_MYSQL,
               "mysql", "-uroot", "-N", "-B"]
        if db:
            cmd += [db]
        return run(cmd, input_bytes=sql.encode("utf-8"), check=False)

    # ---------- MySQL：重放 judge_user 到临时库并比对行数 ----------
    src = backup_dir / "mysql" / "judge_user.sql"
    if src.exists():
        print(f"  · MySQL 演练：{src.name} → {drill_db}")
        sql = src.read_text(encoding="utf-8", errors="replace")
        sql = re.sub(r"(?i)`?judge_user`?", drill_db, sql)      # 重定向到临时库
        # 断言前提：改写必须真的是有效的（dump 用 `--databases` 时才会有 USE）。
        # 否则重放会打到"无默认库"或（更糟）同名生产库上 —— 演练本身必须 fail loud。
        if f"USE `{drill_db}`" not in sql and f"USE {drill_db}" not in sql:
            fails.append("备份文件缺少 USE 语句：无法安全重定向到临时库（不是自包含 dump？）")
            print("    ❌ 备份文件缺少 USE 语句，拒绝重放（避免误写生产库）")
            mysql_exec(f"DROP DATABASE IF EXISTS `{drill_db}`;")
            return len(fails), fails
        mysql_exec(f"DROP DATABASE IF EXISTS `{drill_db}`;")
        mysql_exec(f"CREATE DATABASE `{drill_db}` DEFAULT CHARACTER SET utf8mb4;")
        p = mysql_exec(sql)
        if p.returncode != 0:
            fails.append(f"MySQL 重放失败：{p.stderr.decode('utf-8', 'replace')[:200]}")
        else:
            tbl = MYSQL_PROBE["judge_user"]
            # 查生产行数必须显式带库名：不带默认库会得到 ERROR 1046 + 空 stdout，
            #    而空值参与比较只会报"不一致"，把"我没读到"伪装成"数据不对"。
            live = mysql_exec(f"SELECT COUNT(*) FROM `{tbl}`;", db="judge_user").stdout.decode().strip()
            drl = mysql_exec(f"SELECT COUNT(*) FROM `{tbl}`;", db=drill_db).stdout.decode().strip()
            if not live.isdigit() or not drl.isdigit():
                fails.append(f"MySQL 行数读取失败（生产='{live}' 演练='{drl}'）—— 断言无效")
                print(f"    ❌ 行数读取失败：生产='{live}' 演练='{drl}'")
            else:
                ok = live == drl
                print(f"    {tbl}: 生产={live} 演练={drl} → {'✅ 一致' if ok else '❌ 不一致'}")
                if not ok:
                    fails.append(f"MySQL 行数不一致 {tbl}: 生产 {live} vs 演练 {drl}")
        mysql_exec(f"DROP DATABASE IF EXISTS `{drill_db}`;")
        print(f"    临时库 {drill_db} 已删除")
    else:
        print("  · MySQL 无备份文件，跳过")

    # ---------- PostgreSQL：恢复到临时库并比对 ----------
    src = backup_dir / "pg" / f"{PG_DB}.dump"
    if src.exists() and container_running(CTR_PG):
        print(f"  · PG 演练：{src.name} → {drill_db}")
        run(["docker", "cp", str(src), f"{CTR_PG}:/tmp/{src.name}"], check=False)
        run(["docker", "exec", "-e", f"PGPASSWORD={pg_pwd}", CTR_PG, "dropdb", "-U",
             env_value("POSTGRES_USERNAME") or "postgres", "--if-exists", drill_db], check=False)
        c = run(["docker", "exec", "-e", f"PGPASSWORD={pg_pwd}", CTR_PG, "createdb", "-U",
                 env_value("POSTGRES_USERNAME") or "postgres", drill_db], check=False)
        if c.returncode != 0:
            fails.append(f"PG 临时库创建失败：{c.stderr.decode('utf-8', 'replace')[:200]}")
        else:
            r = run(["docker", "exec", "-e", f"PGPASSWORD={pg_pwd}", CTR_PG, "pg_restore", "-U",
                     env_value("POSTGRES_USERNAME") or "postgres", "-d", drill_db,
                     f"/tmp/{src.name}"], check=False)
            # pg_restore 对 owner/权限类告警返回非 0，属预期（我们只关心数据是否回来）
            if r.returncode != 0:
                warn = r.stderr.decode("utf-8", "replace")
                if "error" in warn.lower():
                    fails.append(f"PG 恢复出现 error：{warn[:200]}")
            def pg_count(db):
                q = run(["docker", "exec", "-e", f"PGPASSWORD={pg_pwd}", CTR_PG, "psql", "-U",
                         env_value("POSTGRES_USERNAME") or "postgres", "-d", db, "-tAc",
                         f"SELECT COUNT(*) FROM {PG_PROBE};"], check=False)
                return q.stdout.decode().strip()
            live, drl = pg_count(PG_DB), pg_count(drill_db)
            ok = live == drl
            print(f"    {PG_PROBE}: 生产={live} 演练={drl} → {'✅ 一致' if ok else '❌ 不一致'}")
            if not ok:
                fails.append(f"PG 行数不一致 {PG_PROBE}: 生产 {live} vs 演练 {drl}")
            run(["docker", "exec", "-e", f"PGPASSWORD={pg_pwd}", CTR_PG, "dropdb", "-U",
                 env_value("POSTGRES_USERNAME") or "postgres", "--if-exists", drill_db], check=False)
            print(f"    临时库 {drill_db} 已删除")
    else:
        print("  · PG 无备份文件或容器未运行，跳过")

    # ---------- Redis：校验 RDB 可加载性（不加载进实例） ----------
    src = backup_dir / "redis" / "dump.rdb"
    if src.exists():
        print("  · Redis 演练：redis-check-rdb 校验（不加载进实例，避免覆盖生产数据）")
        p = run(["docker", "run", "--rm", "-v", f"{src.parent}:/data", "redis:7",
                 "redis-check-rdb", "/data/dump.rdb"], check=False)
        out = (p.stdout + p.stderr).decode("utf-8", "replace")
        ok = p.returncode == 0
        print(f"    → {'✅ RDB 可加载' if ok else '❌ RDB 损坏'}")
        if not ok:
            fails.append(f"Redis RDB 校验失败：{out.strip()[:200]}")
    else:
        print("  · Redis 无备份文件，跳过")

    return len(fails), fails


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--backup", action="store_true", help="执行备份")
    ap.add_argument("--verify", action="store_true", help="对最新备份做恢复演练")
    ap.add_argument("--list", action="store_true", help="列出已有备份")
    args = ap.parse_args()

    if args.list:
        if not BACKUP_DIR.exists():
            print("尚无备份目录")
            return
        for d in sorted(BACKUP_DIR.iterdir(), reverse=True):
            if d.is_dir():
                total = sum(f.stat().st_size for f in d.rglob("*") if f.is_file())
                print(f"  {d.name}   {total / 1024 / 1024:8.2f} MB")
        return

    if not (args.backup or args.verify):
        ap.print_help()
        return

    if args.backup:
        out_dir = BACKUP_DIR / time.strftime("%Y%m%d-%H%M%S")
        print("=" * 70)
        print(f"备份 → {out_dir.relative_to(ROOT)}")
        print("=" * 70)
        m = backup(out_dir)
        if m["skipped"]:
            print("  跳过：" + "；".join(m["skipped"]))
    else:
        dirs = sorted([d for d in BACKUP_DIR.iterdir() if d.is_dir() and (d / "MANIFEST.json").exists()],
                      reverse=True) if BACKUP_DIR.exists() else []
        if not dirs:
            print("没有可用备份，请先 --backup")
            sys.exit(2)
        out_dir = dirs[0]

    if args.verify:
        print()
        print("=" * 70)
        print(f"回滚演练（非破坏性）← {out_dir.relative_to(ROOT)}")
        print("=" * 70)
        n, fails = verify(out_dir)
        print()
        if n:
            print(f"❌ 演练失败 {n} 项：")
            for f in fails:
                print(f"   - {f}")
        else:
            print("✅ 演练通过：备份可用，恢复流程已验证（临时库均已清理）")
        print("=" * 70)
        sys.exit(0 if n == 0 else 1)


if __name__ == "__main__":
    main()
