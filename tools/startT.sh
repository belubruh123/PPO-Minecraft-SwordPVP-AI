#!/usr/bin/env bash
# startT.sh FROM: 21 Oct 19:30. Waits for the dev client's world, starts branch T (trainGT.sh: the tactics model, the
# user's decision-model idea) from FROM, checks the first updates log the tactics lines, then runs the hourly loop with the
# candidate's frozen runs playing its tactics (CANDX) and the installed control without (FROZEN has tacticModel=false).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
echo 0 > hourly-state.txt
bash trainGT.sh "$from"
echo "=== $(date +%H:%M:%S) T training (tactics model) from $from"
CANDX="tacticModel=true" bash hourlycheck2.sh trainGT.sh 40
