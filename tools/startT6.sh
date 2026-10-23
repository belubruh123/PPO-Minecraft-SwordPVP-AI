#!/usr/bin/env bash
# startT6.sh FROM: 23 Oct. T6 = T5 + the combo-escape rule (trainGT6.sh), the hourly loop hourlycheck3.sh with the candidate's
# frozen runs playing the rule (CANDX comboStrafeRule=true) and the installed control without it (FROZEN pins it false).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
echo 0 > hourly-state.txt
bash trainGT6.sh "$from"
echo "=== $(date +%H:%M:%S) T6 training (T5 + combo-escape rule) from $from"
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true comboStrafeRule=true" bash hourlycheck3.sh trainGT6.sh 40
