#!/usr/bin/env bash
# startRules.sh BLOCKS: 22 Oct. Waits for the dev client's world, then measures the two COACH COUNTERS on their own: the
# INSTALLED network with them (candidate, CANDX) against the same network without them, on identical fight sets, order
# alternating block by block (finalpair2.sh). Rules need no training, so this is their direct effect.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; grep -aq "^exit code" client.log && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; tail -20 client.log; exit 1; }
echo "=== $(date +%H:%M:%S) rules check (installed net with/without keepWithin 2.3 + jumpAtReadyOnlyFleeing), $1 blocks"
CANDX="keepWithin=2.3 jumpAtReadyOnlyFleeing=true" bash finalpair2.sh /d/swordai/run/kept-v21-final-1950 RULES "$1"
echo "=== $(date +%H:%M:%S) rules check done"
