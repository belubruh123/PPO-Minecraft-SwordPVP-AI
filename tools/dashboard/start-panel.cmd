@echo off
rem The SwordAI school progress panel: starts its small local server (if it is not running) and opens the page.
cd /d "%~dp0..\.."
powershell -NoProfile -Command "try { Invoke-WebRequest -UseBasicParsing http://localhost:8765/api/data -TimeoutSec 2 | Out-Null; exit 0 } catch { exit 1 }"
if errorlevel 1 start "SwordAI panel" /min python tools\dashboard\server.py
timeout /t 2 /nobreak >nul
start "" http://localhost:8765
