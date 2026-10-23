#!/usr/bin/env bash
# startT9.sh FROM: 23 Oct 17:20. T9 = T8 + the crit trade rules + the jump-in drill (trainGT9.sh), paired hourly loop with a
# drill block (hourlycheck5.sh); the candidate plays with the rules on (CANDX), the installed control with them off.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
echo 0 > hourly-state.txt
bash trainGT9.sh "$from"
echo "=== $(date +%H:%M:%S) T9 training (T8 + crit trade + jump-in drill) from $from"
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true critBridge=true rhythmNet=true holdOnFallingJumper=true instantPunish=true" bash hourlycheck5.sh trainGT9.sh 40
