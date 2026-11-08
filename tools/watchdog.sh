#!/usr/bin/env bash
# watchdog.sh: run by the Windows task SwordAI-Watchdog every 10 minutes, outside the Claude app, so an app restart (28 Oct
# 06:41: an update killed training for 12 hours) cannot kill what it starts. Training is meant to run unless
# school-cycle.stop exists (a redeploy in progress, or stopped on purpose). If the client log has not been written for
# 10 minutes it restarts the client in school mode; if the hourly check loop is gone it starts that again.
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
L=/d/swordai/run/school-client.log
LOG=/d/swordai/run/watchdog.log
LOCK=/d/swordai/run/watchdog.lock
# Everything this run starts goes into a hidden console of its own (7 Nov): attached to this run's console it kept the
# task's run open, and Task Scheduler then skipped every later run as "already running" (00:06-00:35 on 7 Nov). The
# command goes through a script file because Start-Process passes an argument with spaces unquoted.
detach() {  # detach LOGFILE COMMAND...: the command run by Git's bash from /d/swordai, its output to LOGFILE
  local log="$1"; shift
  local f=/d/swordai/run/detach-$$-$RANDOM.sh
  printf 'cd /d/swordai\n%s > %s 2>&1\nrm -f %s\n' "$*" "$log" "$f" > "$f"
  powershell -NoProfile -Command "Start-Process -WindowStyle Hidden -FilePath 'C:\\Program Files\\Git\\bin\\bash.exe' -ArgumentList '-l','$f'"
}
# The progress panel (tools/dashboard, port 8765, 6 Nov): started again when nothing answers there, so it outlives the app.
if ! curl -s -m 5 -o /dev/null http://localhost:8765/; then
  echo "$(date '+%m-%d %H:%M') the panel does not answer: starting it" >> $LOG
  detach /d/swordai/run/panel.out C:/Python38/python.exe tools/dashboard/server.py
fi
# A redeploy asked for (7 Nov): run/redeploy-request (its text: optional VAR=value settings for it). Run from here, the client
# and the hourly loop it starts outlive the Claude app (on 6 Nov a client started from a session died with it at 07:13).
# A redeploy is going on only while its log is under 30 minutes old and has not printed its last line (7 Nov: the script stays
# listed as the hourly loop's parent long after it ended, which blocked every later redeploy for an hour).
redeploying() {
  local r; r=$(ls -t /d/swordai/run/redeploy-*.log 2>/dev/null | head -1)
  [ -n "$r" ] && [ $(( $(date +%s) - $(stat -c %Y "$r") )) -lt 1800 ] \
    && ! grep -aqE "=== cycle restarted|tests failed|world never loaded|nothing restarted" "$r"
}
# Never in the middle of a benchmark (it has a test network in the training's place).
if [ -f /d/swordai/run/redeploy-request ] && ! redeploying && ! ps -ef | grep -a "bench-run" | grep -qv grep; then
  ENVLINE=$(head -1 /d/swordai/run/redeploy-request | tr -d '\r')
  mv /d/swordai/run/redeploy-request /d/swordai/run/redeploy-request.taken
  echo "$(date '+%m-%d %H:%M') redeploy asked for ($ENVLINE)" >> $LOG
  detach "/d/swordai/run/redeploy-$(date +%m%d-%H%M).log" env $ENVLINE bash "$S/school-redeploy.sh"
  exit 0
fi
# A benchmark asked for (7 Nov): run/bench-request holds "TAG NET"; started here, outside the Claude app, so a session that
# ends cannot leave a test network in the place of the training's (tools/bench-run.sh swaps it in and back).
if [ -f /d/swordai/run/bench-request ] && ! ps -ef | grep -a "bench-run" | grep -qv grep; then
  read -r BTAG BNET BSUITE BOPT < /d/swordai/run/bench-request  # "TAG NET [SUITE]", SUITE e.g. suite-v2 (default: bench-run.sh's)
  BNET=$(echo "$BNET" | tr -d '\r'); BSUITE=$(echo "$BSUITE" | tr -d '\r'); BOPT=$(echo "$BOPT" | tr -d '\r')
  BENV=; [ "$BOPT" = hitout ] && BENV="HIT_AND_OUT=1"  # a 4th word "hitout": side A with hit and out (bench-run.sh)
  mv /d/swordai/run/bench-request /d/swordai/run/bench-request.taken
  echo "$(date '+%m-%d %H:%M') benchmark asked for: $BTAG $BNET $BSUITE" >> $LOG
  if [ -n "$BSUITE" ]; then
    detach "/d/swordai/run/bench-$BTAG.out" env SUITE="$S/bench/$BSUITE.txt" $BENV bash "$S/bench-run.sh" "$BTAG" "$BNET"
  else
    detach "/d/swordai/run/bench-$BTAG.out" bash "$S/bench-run.sh" "$BTAG" "$BNET"
  fi
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
# Training stopped with the client still up (7 Nov 07:55: "the server did not start the next fight"; the server's "Can't keep up"
# lines kept the log new for 23 minutes): the age of the last school line counts too.
last=$(grep -a "(swordai) school \|school bench\|school pair" $L 2>/dev/null | tail -1 | grep -o '^\[[0-9:]*\]' | tr -d '[]')
if [ -n "$last" ] && [ $age -lt 600 ]; then
  ls_=$(( $(date -d "$last" +%s 2>/dev/null || date +%s) ))
  [ $ls_ -gt $(date +%s) ] && ls_=$(( ls_ - 86400 ))
  sage=$(( $(date +%s) - ls_ ))
  [ $sage -ge 600 ] && { echo "$(date '+%m-%d %H:%M') no school line for $sage s (training stopped, client up)" >> $LOG; age=$sage; }
fi
# One line a run (8 Nov: the client froze at 15:58 on a sound error, its log kept new by "Can't keep up" lines, and no run
# restarted it for 90 minutes): whether the runs reach this check at all, and what they saw.
echo "$(date '+%m-%d %H:%M') log age $age s, last school line ${last:-none}" >> /d/swordai/run/watchdog-trace.log
if [ $age -ge 600 ]; then
  touch $LOCK
  echo "$(date '+%m-%d %H:%M') the client log is $age s old: restarting the client" >> $LOG
  bash "$S/school-restart.sh" >> $LOG 2>&1
  rm -f $LOCK
fi
if [ "$(cycles | tr -d '\r')" = "0" ] && [ ! -f /d/swordai/run/school-cycle.stop ]; then
  echo "$(date '+%m-%d %H:%M') no hourly check loop: starting it" >> $LOG
  detach /d/swordai/run/school-cycle.out "cd /d/swordai/run && bash $S/schoolcycle.sh"
fi
exit 0
