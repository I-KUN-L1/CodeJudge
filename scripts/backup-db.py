# -*- coding: utf-8 -*-
"""
C2: CodeJudge 数据库备份脚本（MySQL 全量 + PostgreSQL 全量 + Redis 持久化检查）

用法（仓库根）:
    python scripts/backup-db.py                 # 备份到 backups/db/（自动带时间戳）
    python scripts/backup-db.py --keep 14       # 备份后清理 14 天前的旧备份

设计:
    - 经 docker exec 调容器内 mysqldump / pg_dumpall，口令不进命令行（读容器环境）；
    - 输出 UTF-8（--default-character-set=utf8mb4，规避 latin1 双重编码家族坑）；
    - 每次备份后打印行数与大小作为完整性下界证据；
    - 保留策略: --keep N 按 mtime 删 N 天前的 *.sql（默认不删）。
"""
import argparse
import datetime as dt
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = ROOT / "backups" / "db"


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", **kw)
    if r.returncode != 0:
        print(f"[FAIL] {' '.join(cmd)}\n{r.stderr[:2000]}", file=sys.stderr)
        sys.exit(1)
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", type=int, default=0, help="保留 N 天内的备份（0=不清理）")
    args = ap.parse_args()

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    mysql_out = OUT_DIR / f"mysql-all-{stamp}.sql"
    sql = run(["docker", "exec", "codejudge-mysql", "sh", "-c",
               'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --all-databases '
               '--default-character-set=utf8mb4 --single-transaction --routines'])
    mysql_out.write_text(sql, encoding="utf-8")
    print(f"OK  MySQL  -> {mysql_out}  ({len(sql.splitlines())} 行, {mysql_out.stat().st_size} B)")

    pg_out = OUT_DIR / f"pg-all-{stamp}.sql"
    pg = run(["docker", "exec", "codejudge-pg", "pg_dumpall", "-U", "postgres"])
    pg_out.write_text(pg, encoding="utf-8")
    print(f"OK  PG     -> {pg_out}  ({len(pg.splitlines())} 行, {pg_out.stat().st_size} B)")

    redis_conf = run(["docker", "exec", "codejudge-redis",
                      "sh", "-c", 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning '
                      "CONFIG GET appendonly 2>/dev/null; "
                      'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning '
                      "CONFIG GET save 2>/dev/null"])
    print("Redis 持久化配置（appendonly / save）:")
    print(redis_conf.strip())

    if args.keep > 0:
        cutoff = dt.datetime.now().timestamp() - args.keep * 86400
        n = 0
        for f in OUT_DIR.glob("*.sql"):
            if f.stat().st_mtime < cutoff:
                f.unlink()
                n += 1
        print(f"清理 {args.keep} 天前旧备份: {n} 个")
    print("提示: 恢复演练见 scripts/restore-drill.py（空实例灌入 + verify-p1 断言）")


if __name__ == "__main__":
    main()
