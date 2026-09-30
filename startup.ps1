# ============================================================
# CodeJudge startup.ps1 —— startup.sh 的 Windows PowerShell 对应物
# 单服务启动（-c/-n/-d/-p 齐全）或透传 python startup.py 综合入口。
# ASCII-only：PS5.1 对无 BOM UTF-8 按 GBK 解析，中文注释会直接语法错（docs/CONTEXT.md §7）
# ============================================================
param(
    [string]$c = "",
    [string]$n = "",
    [string]$d = "",
    [string]$p = "",
    [switch]$h,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Passthrough
)

$ErrorActionPreference = "Stop"

function Show-Usage {
    Write-Host @"
CodeJudge startup.ps1

Single-service mode (all four required):
  -c  module name, e.g. judge-auth
  -n  process/log name, e.g. judge-auth
  -d  jar path (relative to repo root or absolute)
  -p  listen port, e.g. 9081

Composite mode (passthrough to python startup.py):
  .\startup.ps1 --all --wait

  -h  show this help
"@
}

if ($h) { Show-Usage; exit 0 }

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $repoRoot

if ($c -or $n -or $d -or $p) {
    foreach ($pair in @(@("c", $c), @("n", $n), @("d", $d), @("p", $p))) {
        if ([string]::IsNullOrEmpty($pair[1])) {
            Write-Error "Error: missing value for -$($pair[0])"
            exit 1
        }
    }
    if (-not (Test-Path $d)) {
        Write-Error "Error: jar not found: $d"
        exit 1
    }
    # Host injects SERVER__PORT/SERVER__HOST into child JVMs; must clear them
    # or Spring relaxed binding overrides --server.port (docs/CONTEXT.md SS3.13)
    Remove-Item Env:SERVER__PORT -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER__HOST -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER_PORT -ErrorAction SilentlyContinue
    Remove-Item Env:SERVER_HOST -ErrorAction SilentlyContinue
    Write-Host "[startup.ps1] starting $n (port=$p, jar=$d)"
    & java -jar $d "--server.port=$p"
    exit $LASTEXITCODE
}

# Composite mode: passthrough to startup.py
$python = if ($env:PYTHON) { $env:PYTHON } else { "python" }
& $python (Join-Path $repoRoot "startup.py") @Passthrough
exit $LASTEXITCODE
