#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重新生成首个管理员的初始凭据文件 `<仓库根>/.bootstrap-credentials`。

────────────────────────────────────────────────────────────────────────────────
什么时候需要它
────────────────────────────────────────────────────────────────────────────────
这个文件**正常只由 judge-auth 的启动引导创建一次**（AdminBootstrapService.bootstrapIfNeeded）：
部署后数据库里还没有管理员时，服务创建管理员并把初始口令写进该文件；管理员首次登录后
用 `POST /accounts/password/first-change` 改密，成功后文件被删除，登录响应里的
`mustChangePassword` 随之回到 false。

但下面两种情形下它不会自己出现，只能手工补：

  ① 数据库里**已经有管理员**了（比如跑完 `scripts/reset-demo-data.py` 灌了种子账号），
     引导逻辑会打印「已存在管理员账号，跳过管理员引导」直接返回；
  ② 想**演示/复测前端那条「初始凭据未消费」提示**，而文件此前已被消费（删除）。

────────────────────────────────────────────────────────────────────────────────
两个必须知道的后果
────────────────────────────────────────────────────────────────────────────────
  · 只要这个文件存在，`isBootstrapPending()` 就为 true，**任何 STAFF 账号**登录后拿到的
    `mustChangePassword` 都是 true，登录页会显示「初始凭据文件仍存在」的提示。
    这是"引导态探针"的设计意图，不是 bug —— 但请知悉它会把登录响应伪造成"需要改密"。
  · 因此**不要把它留在不该留的地方**：验收/测试脚本跑完必须保证它不存在
    （`scripts/verify-p1-login.py` 第 7 段专门盯这一点，它会把自己造的哨兵清理干净）。

────────────────────────────────────────────────────────────────────────────────
它会校验什么
────────────────────────────────────────────────────────────────────────────────
写文件**之前**先用网关登录一次，确认 `.env` 里的手机号+口令**真的能登进去**。
宁可失败也不写一份登不上去的凭据 —— 那种文件比没有更坏：运维会照着它去改密，
然后在"原密码错误"上耗掉半小时。

用法（仓库根目录）：
    python scripts/generate-bootstrap-credentials.py --dry-run   # 只看会发生什么（默认）
    python scripts/generate-bootstrap-credentials.py --yes       # 真正写文件
    python scripts/generate-bootstrap-credentials.py --yes --force   # 覆盖已存在的文件
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_PATH = os.path.join(ROOT, ".env")
GATEWAY = "http://127.0.0.1:9080"
# Git Bash 下 curl/urllib 会走宿主代理，127.0.0.1 必须显式绕过
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

SENTINEL_MARK = "generate-bootstrap-credentials.py"


def log(msg=""):
    print(msg, flush=True)


def env_value(key, default=""):
    """从 .env 读一个值。不回显内容 —— 调用方负责只打印长度或存在性。"""
    try:
        with open(ENV_PATH, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                if k.strip() == key:
                    return v.strip().strip('"').strip("'")
    except FileNotFoundError:
        pass
    return default


def call(method, path, body=None, token=None, timeout=15):
    req = urllib.request.Request(GATEWAY + path, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(body).encode() if body is not None else None
    try:
        with OPENER.open(req, data, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw or "{}")
        except Exception:
            return e.code, {"raw": raw[:200]}
    except Exception as e:  # 连不上网关
        return 0, {"raw": f"{type(e).__name__}: {e}"}


def login(phone, password, tries=8):
    """登录并返回 (body, token)。网关登录令牌桶 2 req/s（突发 5），空响应体 = 被限流。"""
    for i in range(tries):
        st, body = call("POST", "/accounts/login", {"cellPhone": phone, "password": password})
        if st == 0:
            return None, None
        if body and body.get("data"):
            return body, body["data"].get("accessToken")
        # 业务侧的明确拒绝（口令错/账号不存在）不重试，避免把口令错误拖成"限流"误导
        if body.get("code") not in (None, 429) and body.get("msg"):
            return body, None
        time.sleep(0.9)
    return None, None


def build_content(phone, password):
    """与 AdminBootstrapService.writeCredentialFile 逐字节一致（含末尾那个换行）。"""
    fmt = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    return "\n".join([
        "== CodeJudge 首个管理员初始凭据 ==",
        "生成时间：" + fmt,
        "说明：文件仅首次启动生成，登录后请立即修改密码，修改成功后该文件会被自动删除。",
        "手机号/账号：" + phone,
        "初始密码：" + password + "\n",
    ])


def main():
    ap = argparse.ArgumentParser(description="生成 .bootstrap-credentials（默认预演）")
    ap.add_argument("--yes", action="store_true", help="真正写文件（默认只预演）")
    ap.add_argument("--force", action="store_true", help="文件已存在时覆盖")
    args = ap.parse_args()

    phone = env_value("CJ_ADMIN_PHONE", "13800000000")
    password = env_value("CJ_ADMIN_INIT_PASSWORD")
    cred_rel = env_value("CJ_ADMIN_CREDENTIAL_FILE", ".bootstrap-credentials")
    cred_path = cred_rel if os.path.isabs(cred_rel) else os.path.join(ROOT, cred_rel)

    log("=" * 78)
    log("生成 .bootstrap-credentials　模式：" + ("执行" if args.yes else "预演"))
    log("=" * 78)
    log(f"管理员手机号        : {phone}")
    log(f".env 口令           : {'已配置，长度 ' + str(len(password)) if password else '★ 未配置（CJ_ADMIN_INIT_PASSWORD 为空）'}")
    log(f"凭据文件绝对路径    : {cred_path}")
    log(f"文件当前状态        : {'已存在' if os.path.exists(cred_path) else '不存在'}")

    if not password:
        log("\n★ .env 里没有 CJ_ADMIN_INIT_PASSWORD —— 无法确定当前管理员口令，拒绝生成。")
        log("  该文件的作用是告诉运维\"当前口令是什么\"，猜一个值写进去等于埋雷。")
        sys.exit(1)

    if os.path.exists(cred_path) and not args.force:
        log("\n文件已存在且未加 --force —— 保持原样退出（避免覆盖掉别人正在用的那份）。")
        log("  如需重建：python scripts/generate-bootstrap-credentials.py --yes --force")
        sys.exit(0)

    # ---- 写前校验：这份凭据必须真的能登进去 --------------------------------
    log("\n[1/3] 校验凭据可用性（网关登录）")
    body, token = login(phone, password)
    if token:
        role = (body.get("data") or {}).get("role")
        log(f"  ✓ 登录成功（role={role}）")
    else:
        log("  ★ 登录失败 —— 不写文件。")
        if body:
            log(f"    网关返回：{json.dumps(body, ensure_ascii=False)[:300]}")
        else:
            log(f"    网关 {GATEWAY} 不可达（服务没起？）")
        log("    可能原因：① .env 里的口令与库里不一致（管理员口令被轮换过）；")
        log("             ② 登录被网关令牌桶限流（2 req/s，突发 5）—— 稍等重试。")
        sys.exit(1)

    # ---- 写 ---------------------------------------------------------------
    content = build_content(phone, password)
    log("\n[2/3] 写入凭据文件")
    if args.yes:
        with open(cred_path, "w", encoding="utf-8", newline="") as fh:
            fh.write(content)
        log(f"  ✓ 已写入 {cred_path}（{len(content.encode('utf-8'))} 字节，{len(content.splitlines())} 行）")
    else:
        log("  · 预演：跳过写入。将写入的内容结构如下（口令不回显）：")
        for line in content.splitlines():
            if line.startswith("初始密码："):
                log(f"      {line[:5]}<{len(password)} 位，已隐藏>")
            else:
                log(f"      {line}")

    # ---- 写后验证：必须让登录响应报出 mustChangePassword=true --------------
    log("\n[3/3] 验证「引导态」已被前端感知")
    if not args.yes:
        log("  · 预演：未写文件，跳过。")
    else:
        time.sleep(1.0)
        body2, _ = login(phone, password)
        if not body2:
            log("  ★ 复登失败（可能被限流），请稍后手工复验")
            sys.exit(1)
        flag = (body2.get("data") or {}).get("mustChangePassword")
        log(f"  登录响应 mustChangePassword = {flag}（期望 True）"
            f" —— 前端登录页会提示「初始凭据文件 .bootstrap-credentials 仍存在」")
        if flag is not True:
            log("  ★ 不是 True：可能文件被 AdminBootstrapService 之外的路径删掉了，或写得不对")
            sys.exit(1)

        # fail-closed：拿错误旧口令改密必须失败，且**文件不能被删**
        time.sleep(1.0)
        st3, body3 = call("POST", "/accounts/password/first-change", {
            "cellPhone": phone, "oldPassword": "definitely-wrong-" + SENTINEL_MARK,
            "newPassword": "neverUsed123",
        })
        still = os.path.exists(cred_path)
        log(f"  用错误旧口令改密：HTTP {st3} code={body3.get('code')} msg={body3.get('msg')}")
        log(f"  改密失败后文件仍在    = {still}（期望 True —— 失败路径不得删除凭据）")
        if not still:
            log("  ★ 文件被误删！这是 AdminBootstrapService 的 fail-closed 承诺被破坏")
            sys.exit(1)

    log("")
    log("=" * 78)
    if args.yes:
        log("完成。接下来：用上面的手机号 + 口令登录管理端，改密成功后该文件自动删除，")
        log("      登录响应的 mustChangePassword 随之回到 false。")
    else:
        log("预演结束，未改动任何东西。真正执行请加 --yes")
    log("=" * 78)


if __name__ == "__main__":
    main()
