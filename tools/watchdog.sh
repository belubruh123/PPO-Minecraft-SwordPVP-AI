#!/usr/bin/env bash
# watchdog.sh: run by the Windows task SwordAI-Watchdog every 10 minutes, outside the Claude app, so an app restart (28 Oct
# 06:41: an update killed training for 12 hours) cannot kill what it starts. Training is meant to run unless
# school-cycle.stop exists (a redeploy in progress, or stopped on purpose). If the client log has not been written for
# 10 minutes it restarts the client in school mode; if the hourly check loop is gone it starts that again.
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
L=/d/swordai/run/school-client.log
LOG=/d/swordai/run/watchdog.log
LOCK=/d/swordai/run/watchdog.lock
# The progress panel (tools/dashboard, port 8765, 6 Nov): started again when nothing answers there, so it outlives the app.
if ! curl -s -m 5 -o /dev/null http://localhost:8765/; then
  echo "$(date '+%m-%d %H:%M') the panel does not answer: starting it" >> $LOG
  cd /d/swordai && nohup C:/Python38/python.exe tools/dashboard/server.py > /d/swordai/run/panel.out 2>&1 &
  disown
fi
# A benchmark asked for (7 Nov): run/bench-request holds "TAG NET"; started here, outside the Claude app, so a session that
# ends cannot leave a test network in training's place (tools/bench-run.sh swaps it in and back).
if [ -f /d/swordai/run/bench-request ] && ! ps -ef | grep -a "bench-run" | grep -qv grep; then
  read -r BTAG BNET < /d/swordai/run/bench-request
  mv /d/swordai/run/bench-request /d/swordai/run/bench-request.taken
  echo "$(date '+%m-%d %H:%M') benchmark asked for: $BTAG $BNET" >> $LOG
  cd /d/swordai && nohup bash "$S/bench-run.sh" "$BTAG" "$BNET" > "/d/swordai/run/bench-$BTAG.out" 2>&1 &
  disown
  exit 0
fi
[ -f /d/swordai/run/school-cycle.stop ] && exit 0
# A restart of its own still going (it waits up to 10 minutes for the world).
if [ -f $LOCK ] && [ $(( $(date +%s) - $(stat -c %Y $LOCK) )) -lt 1800 ]; then
  exit 0
fi
cycles() {
  powershell -NoProfile -Command "@(Get-CimInstance Win32_Process | Where-Object { \$_.Name -eq 'bash.exe' -and \$_.CommandLine -match 'schoolcycle\.sh' }).Count"
}
age=$(( $(date +%s) - $(stat -c %Y $L 2>/dev/null || echo 0) ))
if [ $age -ge 600 ]; then
  touch $LOCK
  echo "$(date '+%m-%d %H:%M') the client log is $age s old: restarting the client" >> $LOG
  bash "$S/school-restart.sh" >> $LOG 2>&1
  rm -f $LOCK
fi
if [ "$(cycles | tr -d '\r')" = "0" ] && [ ! -f /d/swordai/run/school-cycle.stop ]; then
  echo "$(date '+%m-%d %H:%M') no hourly check loop: starting it" >> $LOG
  cd /d/swordai/run && nohup bash "$S/schoolcycle.sh" > /d/swordai/run/school-cycle.out 2>&1 &
  disown
fi
exit 0
