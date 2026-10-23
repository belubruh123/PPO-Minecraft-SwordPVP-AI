#!/usr/bin/env bash
# startT7.sh FROM: 23 Oct. T7 = T5 + the crit/escape bridge (trainGT7.sh), the hourly loop hourlycheck3.sh with the
# candidate's frozen runs playing it (CANDX critBridge=true) and the installed control without it (FROZEN pins it false).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
echo 0 > hourly-state.txt
bash trainGT7.sh "$from"
echo "=== $(date +%H:%M:%S) T7 training (T5 + crit/escape bridge + critic fight kind) from $from"
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true critBridge=true" bash hourlycheck3.sh trainGT7.sh 40
