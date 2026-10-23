#!/usr/bin/env bash
# startT5.sh FROM: T5 = T4 + learning from the user's recorded fights (trainGT5.sh), loop hourlycheck3.sh.
# startT4b.sh FROM: startT4.sh with the loop that waits for the user's recordings (hourlycheck3.sh).
# startT4.sh FROM: 22 Oct. Waits for the dev client's world, starts branch T4 (trainGT5.sh = T3 + the coach counters keepWithin 2.3 and
# jumpAtReadyOnlyFleeing) from FROM, then the hourly loop with the candidate's frozen runs playing its tactics, reader and
# counters (CANDX) and the installed control without them (FROZEN pins them off).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
echo 0 > hourly-state.txt
bash trainGT5.sh "$from"
echo "=== $(date +%H:%M:%S) T4 training (T3 + coach counters) from $from"
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true" bash hourlycheck3.sh trainGT5.sh 40
