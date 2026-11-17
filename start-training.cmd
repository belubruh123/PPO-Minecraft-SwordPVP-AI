@echo off
rem Starts SwordAI training (a few minutes if the training client has to be started), lets the watchdog work,
rem and opens the dashboard (http://localhost:8765) to see whether it improves: checks, rounds won, punish crit, combos.
rem Close your own Minecraft first: both at once make each other slower.
curl -s -m 3 -o nul http://localhost:8765/ || start "SwordAI dashboard" /min C:\Python38\python.exe D:\swordai\tools\dashboard\server.py
"C:\Program Files\Git\bin\bash.exe" -l D:/swordai/tools/training-start.sh
timeout /t 3 >nul
start "" http://localhost:8765/
pause
