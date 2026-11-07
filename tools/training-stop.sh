#!/usr/bin/env bash
# training-stop.sh: stops SwordAI training (the user's button, 8 Nov; double-click D:\swordai\stop-training.cmd). The
# watchdog stays installed but does nothing while run/school-cycle.stop exists; the hourly check loop ends; the training
# client saves ("swordai stop") and is closed. It never touches the user's own Minecraft (the launcher's javaw): only the
# dev client's processes (start-school.cmd, Gradle), the same match tools/school-restart.sh uses.
# Start again with D:\swordai\start-training.cmd (tools/training-start.sh).
R=/d/swordai/run
if ps -ef | grep -a "bench-run" | grep -qv grep; then
  echo "A benchmark is running (it has a test network in training's place). Wait about 15 minutes and run this again."
  exit 1
fi
if ps -ef | grep -a "school-redeploy" | grep -qv grep && [ -n "$(find $R -maxdepth 1 -name 'redeploy-*.log' -mmin -15)" ]; then
  echo "A redeploy is going on. Wait about 5 minutes and run this again."
  exit 1
fi
touch $R/school-cycle.stop
echo "$(date '+%m-%d %H:%M') training stopped by training-stop.sh" >> $R/watchdog.log
printf 'swordai stop\r\n' > $R/swordai/inbox.txt
echo "Saving and stopping training..."
sleep 10
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'start-school\.cmd|start-dev\.cmd|GradleWrapperMain|GradleDaemon|fabric\.dli\.' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'bash.exe' -and \$_.CommandLine -match 'schoolcycle\.sh' -and \$_.CommandLine -notmatch 'CimInstance' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
echo "Training stopped ($(date '+%m-%d %H:%M')). The watchdog will leave it alone until you run start-training.cmd."
