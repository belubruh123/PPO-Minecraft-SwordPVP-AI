#!/usr/bin/env bash
# playguard5.sh: playguard4 that only resumes training (the loop from startT5b.sh keeps running).
# playguard4.sh FROM: 23 Oct 08:20, playguard3 for T5b, without putting a jar into the profile.
# playguard2.sh FROM: 22 Oct 23:12, playguard.sh that builds the jar first (K early in a fight records that fight).
# playguard.sh FROM: 22 Oct 23:10. The handheld runs either the user's own game (profile DRL AI) or the training client,
# never both (the user plays, records fights with /swordai demo, closes and reopens the game as they like). Every 30 s:
#  - their game is open: if the training client runs, training is stopped (saved) and the client closed; run/swordai/demo.lock
#    makes the hourly loop (hourlycheck3.sh) wait, its clocks standing still;
#  - their game has been closed for 3 minutes: their recorded fights are copied into run/swordai/demos (the ones against the
#    one that stands, plain and people set aside in demos-aside), the new jar is put into their profile once (install3.ps1,
#    same network, backup), and the training client is started: the first time T4 from FROM with the loop (startT5b.sh),
#    afterwards training is taken up where it was ('swordai train': whatever config.json holds, training or a check).
# It also stands in for the watchdog: a training client that is gone is started again the same way.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
prof="/c/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI"
LOCK=/d/swordai/run/swordai/demo.lock
# Only java, javaw and cmd processes: the PowerShell asking has the pattern in its own command line.
count() { powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe' or Name='cmd.exe'\" | Where-Object { \$_.CommandLine -match '$1' } | Measure-Object).Count" | tr -d '\r'; }
user_running() { [ "$(count 'DRL AI')" != "0" ]; }
dev_running() { [ "$(count 'launch\.cfg|start-dev\.cmd|GradleWrapperMain')" != "0" ]; }
# 23 Oct: the profile already has the jar with /swordai demo and the K fix; nothing to put in.
installed=1
# 23 Oct 08:50: T5b and its hourly loop already run; training is only taken up again.
started=1
idle=0
while true; do
  if user_running; then
    idle=0
    if dev_running; then
      echo user > $LOCK
      printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
      sleep 12
      powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'launch\.cfg|start-dev\.cmd|GradleWrapperMain|GradleDaemon' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
      echo "=== $(date +%H:%M:%S) your game opened: training saved and paused"
    fi
    sleep 30
    continue
  fi
  if [ $idle -lt 180 ]; then
    idle=$(( idle + 30 ))
    sleep 30
    continue
  fi
  if ! dev_running; then
    mkdir -p /d/swordai/run/swordai/demos /d/swordai/run/swordai/demos-aside
    cp -n "$prof/swordai/demos/"*.bin.gz /d/swordai/run/swordai/demos/ 2>/dev/null
    for f in /d/swordai/run/swordai/demos/*stands* /d/swordai/run/swordai/demos/*plain* /d/swordai/run/swordai/demos/*people*; do
      [ -f "$f" ] && mv "$f" /d/swordai/run/swordai/demos-aside/
    done
    echo "=== $(date +%H:%M:%S) your game closed: recorded fights to learn from $(ls /d/swordai/run/swordai/demos | wc -l), set aside $(ls /d/swordai/run/swordai/demos-aside | wc -l)"
    if [ $installed -eq 0 ]; then
      cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > build-guard.log 2>&1
      echo "=== build: $(grep -a 'BUILD' build-guard.log | tail -1)"
      powershell -NoProfile -ExecutionPolicy Bypass -File install3.ps1 -Net 'D:\swordai\run\kept-v21-final-1950' -Version '0.15.4' > install-guard.log 2>&1
      if grep -aq "jar  same: True" install-guard.log; then installed=1; fi
      echo "=== install into your profile: $(grep -a 'jar  same\|net  same\|running' install-guard.log | tr -d '\r' | tr '\n' ' ')"
    fi
    n=$(ls client-run*-guard*.log 2>/dev/null | wc -l)
    cp client.log client-run-guard$n.log 2>/dev/null
    cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
    sleep 20
    if [ $started -eq 0 ]; then
      started=1
      bash startT5b.sh "$from" &
      echo "=== $(date +%H:%M:%S) training client starting; T5b from $from with the hourly loop"
    else
      for i in $(seq 1 60); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
      sleep 5
      rm -f $LOCK
      printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
      echo "=== $(date +%H:%M:%S) training taken up again"
    fi
  fi
  sleep 30
done
