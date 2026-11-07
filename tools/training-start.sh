#!/usr/bin/env bash
# training-start.sh: starts SwordAI training again (the user's button, 8 Nov; double-click D:\swordai\start-training.cmd).
# Lets the watchdog work again (removes run/school-cycle.stop), starts the training client unless one is running (build,
# game, "swordai school": tools/school-restart.sh, a few minutes), then runs the watchdog once so the hourly check loop
# starts. Stop it with D:\swordai\stop-training.cmd (tools/training-stop.sh).
R=/d/swordai/run
S=/d/swordai/tools
rm -f $R/school-cycle.stop
echo "$(date '+%m-%d %H:%M') training started by training-start.sh" >> $R/watchdog.log
running=$(powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'java.exe' -and \$_.CommandLine -match 'fabric\.dli\.' }).Count" | tr -d '\r')
if [ "$running" = "0" ]; then
  echo "Starting the training client (build and game, a few minutes)..."
  bash $S/school-restart.sh
else
  echo "The training client is already open: telling it to train."
  printf 'swordai school\r\n' > $R/swordai/inbox.txt
  sleep 10
fi
bash $S/watchdog.sh
echo "Training started ($(date '+%m-%d %H:%M')). The hourly check loop runs from now; stop it with stop-training.cmd."
