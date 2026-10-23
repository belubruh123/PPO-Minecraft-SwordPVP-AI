#!/usr/bin/env bash
# watchdog.sh LOOP_OUTPUT TRAIN_SCRIPT: the dev client crashed on 21 Oct 04:51 (GPU fence, a graphics driver fault) and
# training stopped. Every 2 minutes: if start-dev.cmd has written "exit code" to client.log (the client is gone), the log
# is kept, the client is started again (on screen), and once the world is loaded training is resumed from the saved
# checkpoint with TRAIN_SCRIPT - but only when the hourly loop is in its training phase (its last "===" line says
# training); during a check the loop's own next run sets the client up. Logged to watchdog.log.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
loopout=$1; train=$2
while true; do
  sleep 120
  if tail -3 client.log 2>/dev/null | grep -aq "^exit code"; then
    n=$(ls client-run*-auto*.log 2>/dev/null | wc -l)
    cp client.log client-run-auto$n.log
    echo "$(date +%H:%M:%S) client gone ($(grep -a 'Description:' -A0 client.log | tail -1)); restarting" >> watchdog.log
    cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
    sleep 20
    for i in $(seq 1 60); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
    phase=$(grep -a "^===" "$loopout" | tail -1)
    if echo "$phase" | grep -q "training"; then
      bash $train > /dev/null
      echo "$(date +%H:%M:%S) world loaded; training resumed with $train (loop: $phase)" >> watchdog.log
    else
      echo "$(date +%H:%M:%S) world loaded; loop is checking, left to it (loop: $phase)" >> watchdog.log
    fi
  fi
done
