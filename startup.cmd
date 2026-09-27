@echo off
REM ============================================================
REM  CodeJudge one-shot launcher (double-click / cmd entry).
REM  Real logic lives in startup.py; this wrapper only locates
REM  Python and forwards arguments. ASCII-only on purpose:
REM  cmd batch + UTF-8 Chinese text is a mojibake trap.
REM ============================================================
setlocal
set "ROOT=%~dp0"

where python >nul 2>nul
if errorlevel 1 (
  echo [ERROR] python not found in PATH. Install Python 3.8+ first.
  pause
  exit /b 1
)

python "%ROOT%startup.py" %*
set "EC=%errorlevel%"

if "%EC%" neq "0" (
  echo.
  echo [ERROR] startup failed with exit code %EC%. See messages above.
  pause
)
exit /b %EC%
