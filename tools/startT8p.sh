#!/usr/bin/env bash
# startT8p.sh FROM: 23 Oct. T8 = T7 + the memory (trainGT8.sh), the PAIRED hourly loop hourlycheck4.sh with the
# candidate's frozen runs playing it (CANDX critBridge=true) and the installed control without it (FROZEN pins it false).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
echo 0 > hourly-state.txt
bash trainGT8.sh "$from"
echo "=== $(date +%H:%M:%S) T8 training (T7 + memory TCN) from $from"
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true critBridge=true rhythmNet=true" bash hourlycheck4.sh trainGT8.sh 40
