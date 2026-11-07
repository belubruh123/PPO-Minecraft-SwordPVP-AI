@echo off
rem Stops SwordAI training (saves first). The watchdog stays installed but leaves training alone until start-training.cmd.
rem Never touches your own Minecraft.
"C:\Program Files\Git\bin\bash.exe" -l D:/swordai/tools/training-stop.sh
pause
