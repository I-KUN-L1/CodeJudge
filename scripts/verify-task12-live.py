#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""三项改动的上线后 API 验证（任务 1 建号收紧 + 任务 2 actuator CORS）。

只读 + 一条受控的写操作（创建教师账号随后由验证脚本自己确认），不清洗数据。
用法：python scripts/verify-task12-live.py
"""
import json
import os
import pathlib
import sys
import time

import requests

ROOT = pathlib.Path(__file__).resolve().parent.parent
GATEWAY = "http://localhost:9080"
PROXIES = {"http": None, "https": None}

PASS, FAIL = 0, 0


def check(name, ok, detail=""):
    global PASS, FAIL
    print(f"  [{'PASS' if ok else 'FAIL'}] {name}" + (f" —— {detail}" if detail else ""))
    PASS, FAIL = PASS + (1 if ok else 0), FAIL + (0 if ok else 1)
    return ok


def env(key):
    for line in (ROOT / ".env").read_text(encoding="utf-8", errors="replace").splitlines():
        s = line.strip()
        if s and not s.startswith("#") and "=" in s:
            k, v = s.split("=", 1)
            if k.strip() == key:
                return v.strip()
    return ""


def login(phone, password, tries=15):
    last = ""
    for _ in range(tries):
        r = requests.post(f"{GATEWAY}/accounts/login",
                          json={"cellPhone": phone, "password": password},
                          timeout=10, proxies=PROXIES)
        try:
            data = r.json()
        except ValueError:
            last = f"HTTP {r.status_code}"
            time.sleep(0.8)
            continue
        token = (data.get("data") or {}).get("accessToken")
        if token:
            return {"Authorization": f"Bearer {token}"}
        if r.status_code == 429 or data.get("code") == 429:
            time.sleep(0.8)
            continue
        raise SystemExit(f"登录失败 {phone}: {json.dumps(data, ensure_ascii=False)[:200]}")
    raise SystemExit(f"登录退避 {tries} 次仍被拒 {phone}: {last}")


def main():
    # ── 任务 2：actuator CORS（浏览器直连 908x 的跨源探测此前全被拦）──
    origin = "http://localhost:5174"
    ok_any, checked = 0, 0
    for port in (9080, 9081, 9082, 9086):
        checked += 1
        try:
            r = requests.get(f"http://localhost:{port}/actuator/health",
                             headers={"Origin": origin}, timeout=8, proxies=PROXIES)
            acao = r.headers.get("Access-Control-Allow-Origin", "")
            body_up = (r.json() or {}).get("status") == "UP"
            ok_any += 1 if (acao and body_up) else 0
            print(f"    · :{port} status={r.json().get('status')} ACAO={acao or '（无）'}")
        except Exception as e:
            print(f"    · :{port} 不可达：{e}")
    check("actuator 带 Origin 返回 ACAO 且 health=UP（8 服务抽 4）", ok_any == checked,
          f"{ok_any}/{checked}")

    # ── 任务 1：POST /users 强制教师 ──
    admin = login(env("CJ_ADMIN_PHONE") or "13800000000", env("CJ_ADMIN_INIT_PASSWORD"))
    stamp = str(int(time.time()))
    phone = "139" + stamp[-8:]  # 3+8=11 位，符合 ^1\d{10}$
    r = requests.post(f"{GATEWAY}/users", headers=admin,
                      json={"cellPhone": phone, "name": "建号收紧验证",
                            "password": "t123456", "type": 1, "username": "t1verify"},
                      timeout=10, proxies=PROXIES).json()
    check("POST /users 显式传 type=1 仍返回 200", r.get("code") == 200,
          json.dumps(r, ensure_ascii=False)[:120])
    teachers = requests.get(f"{GATEWAY}/teachers/page?pageNo=1&pageSize=50&keyword={phone}",
                            headers=admin, timeout=10, proxies=PROXIES).json()
    rows = (teachers.get("data") or {}).get("list") or []
    hit = next((u for u in rows if u.get("cellPhone") == phone), None)
    check("带 type=1 的请求实际落库为教师（type=3）",
          bool(hit) and hit.get("type") == 3,
          f"type={hit.get('type') if hit else '未找到'}")
    # 清理验证残留账号（软删），不留垃圾
    if hit:
        requests.delete(f"{GATEWAY}/users/{hit['id']}", headers=admin,
                        timeout=10, proxies=PROXIES)

    print(f"\n校验结果：PASS={PASS} FAIL={FAIL}")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
