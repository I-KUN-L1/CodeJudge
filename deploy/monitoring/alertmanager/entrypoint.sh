#!/bin/sh
# ============================================================
# Alertmanager 入口脚本：先把配置模板渲染成实际配置，再启动主进程。
#
# 为什么需要它（这是实测踩出来的，不是设计洁癖）：
#   1. Alertmanager **原生不支持读环境变量**。
#      `--config.expand-env` 属于 **Prometheus**，不是 Alertmanager ——
#      在 prom/alertmanager:v0.27.0 上加这个 flag 会直接 CrashLoop：
#          alertmanager: error: unknown long flag '--config.expand-env'
#      （配置文件里的 ${VAR} 会被当作**字面量字符串**，receiver 名永远匹配不上，
#        告警静默不发 —— 比崩溃更难发现。）
#   2. 项目规范要求「凭据走环境变量、不硬编码进配置文件」，
#      而官方镜像是精简镜像，**没有 envsubst**（只有 sh / sed / awk / grep）。
#
#   于是：用 awk 做一次 ${VAR} 展开。仅替换 ${...} 形式，
#   **不会碰 Go 模板的 {{ .Status }}**（邮件主题里用到）。
#
# 渲染结果自检：若残留 ${...} 说明模板里有拼错的变量名，直接退出而不是带着
# 半成品配置启动 —— 否则又是「启动成功但通知不到人」的静默失败。
# ============================================================
set -e

TPL="${AM_TEMPLATE:-/etc/alertmanager/alertmanager.yml.tpl}"
OUT="${AM_RENDERED:-/alertmanager/rendered-alertmanager.yml}"

if [ ! -f "$TPL" ]; then
    echo "[entrypoint] FATAL: 找不到配置模板 $TPL" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUT")"

# ${VAR} → 环境变量值；未定义则展开为空串。
# 默认值不在这里提供：compose 的 environment 已用 ${VAR:-default} 兜底，
# 保持「默认值只有一个来源」。
awk '
{
    line = $0
    while (match(line, /\$[{][A-Za-z_][A-Za-z0-9_]*(:-[^}]*)?[}]/)) {
        tok = substr(line, RSTART + 2, RLENGTH - 3)   # 去掉 ${ 与 }
        p = index(tok, ":-")
        if (p > 0) {
            name = substr(tok, 1, p - 1)
            def  = substr(tok, p + 2)
        } else {
            name = tok
            def  = ""
        }
        val  = (name in ENVIRON) ? ENVIRON[name] : def
        line = substr(line, 1, RSTART - 1) val substr(line, RSTART + RLENGTH)
    }
    print line
}' "$TPL" > "$OUT"

if grep -qF '${' "$OUT"; then
    echo "[entrypoint] FATAL: 渲染后仍有未展开的变量（模板里可能拼错了变量名）：" >&2
    grep -nF '${' "$OUT" >&2
    exit 1
fi

echo "[entrypoint] 配置已渲染 -> $OUT"
echo "[entrypoint] 默认接收器 = ${ALERTMANAGER_DEFAULT_RECEIVER:-<未设置>} ｜ critical = ${ALERTMANAGER_CRITICAL_RECEIVER:-<未设置>}"

exec /bin/alertmanager --config.file="$OUT" --storage.path=/alertmanager
