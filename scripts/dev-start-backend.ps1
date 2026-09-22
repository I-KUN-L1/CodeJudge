# ==========================================================
# CodeJudge —— 后端一键启动（Windows / PowerShell）
# ----------------------------------------------------------
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\dev-start-backend.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\dev-start-backend.ps1 judge-auth judge-user judge-gateway
#
# 背景：见 scripts/dev-start-backend.sh 顶部说明。
# 宿主环境注入的 SERVER__PORT / SERVER__HOST（含单下划线变体）会被 Spring 松散绑定为
# server.port / server.host，覆盖 application.yml 导致端口冲突而启动失败；
# 同时 %TMP% 可能指向不可写的 C:\Windows\。本脚本启动前统一清理/修正。
#
# ⚠ 绕开本脚本直接 `java -jar` 时务必二选一：先 unset 上述变量，或显式传
#    `--server.port=<端口>`（命令行参数优先级最高）。
# ==========================================================

param([string[]]$Modules)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
Set-Location $Root

# ---------- 定位 Maven（必须用 mvn.cmd：bin/mvn 在 Git Bash/PS 下会报找不到主类） ----------
$Mvn = $env:MVN
if (-not $Mvn) {
  $cmd = Get-Command mvn.cmd -ErrorAction SilentlyContinue
  if ($cmd) {
    $Mvn = $cmd.Source
  } elseif (Test-Path "D:\1\apache-maven-3.9.6\bin\mvn.cmd") {
    $Mvn = "D:\1\apache-maven-3.9.6\bin\mvn.cmd"
  } else {
    throw "未找到 Maven，请设置环境变量 MVN"
  }
}

$TmpLocal = Join-Path $Root "logs\tmp"
New-Item -ItemType Directory -Force -Path $TmpLocal | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $Root "logs") | Out-Null

# ---------- 关键：清除宿主注入的端口变量 ----------
# 已实测确证（2026-09-20）：WorkBuddy 终端会向子 JVM 注入
#   SERVER__PORT = 56298（= 宿主自身监听端口）  SERVER__HOST = 127.0.0.1
# Spring 松散绑定会把它解析为 server.port，优先级高于 application.yml。
# 同时清掉单下划线变体，兼容其他宿主/CI 的命名。
Remove-Item Env:SERVER__PORT -ErrorAction SilentlyContinue
Remove-Item Env:SERVER__HOST -ErrorAction SilentlyContinue
Remove-Item Env:SERVER_PORT  -ErrorAction SilentlyContinue
Remove-Item Env:SERVER_HOST  -ErrorAction SilentlyContinue

# 沙箱下 %TMP% 可能指向不可写的 C:\Windows\，统一指向仓库内可写目录
$env:JAVA_TOOL_OPTIONS = "-Djava.io.tmpdir=$TmpLocal"

# 服务清单；后续阶段把新模块追加进来即可
if (-not $Modules -or $Modules.Count -eq 0) {
  $Modules = @("judge-auth", "judge-user", "judge-problem", "judge-submission", "judge-worker", "judge-gateway")
  # P4+：judge-contest ｜ P5+：judge-ai
}

# ---------- 启动前自检 ----------
if (-not (Test-Path (Join-Path $Root ".env"))) {
  Write-Host "警告：未发现 .env —— 服务会因缺少 MYSQL_PASSWORD / REDIS_PASSWORD / CJ_JWT_SECRET 启动失败。"
  Write-Host "  请先执行：Copy-Item .env.example .env 并填写密码后重试。"
  Write-Host ""
}

Write-Host "仓库根目录: $Root"
Write-Host "Maven     : $Mvn"
Write-Host "启动服务  : $($Modules -join ', ')"
Write-Host "----------------------------------------------------------"

foreach ($m in $Modules) {
  if (-not (Test-Path (Join-Path $Root $m))) {
    Write-Host "  x 模块不存在：$m（已跳过）"
    continue
  }
  Start-Process -FilePath $Mvn `
    -ArgumentList "-pl", $m, "spring-boot:run" `
    -WorkingDirectory $Root `
    -WindowStyle Hidden `
    -RedirectStandardOutput (Join-Path $Root "logs\$m.dev.log") `
    -RedirectStandardError  (Join-Path $Root "logs\$m.dev.err.log")
  Write-Host "  -> $m 已启动   日志: logs\$m.dev.log"
  Start-Sleep -Milliseconds 800
}

Write-Host "----------------------------------------------------------"
Write-Host "已启动 $($Modules.Count) 个服务。"
Write-Host "网关统一入口：http://localhost:9080   接口文档：http://localhost:9080/doc.html"
Write-Host "判断服务是否就绪：curl http://localhost:<port>/actuator/health"
