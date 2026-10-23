#!/usr/bin/env bash
# startEdge.sh BLOCKS: 22 Oct. The crit in (Config.edgeCritRule, JumpIn.EDGE) measured on its own: the INSTALLED network with
# the two coach counters on in both arms (INSTX), and the crit in on top in the candidate arm (CANDX); same fight sets, order
# alternating (finalpair2.sh).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; grep -aq "^exit code" client.log && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; tail -20 client.log; exit 1; }
echo "=== $(date +%H:%M:%S) edge crit check (installed net + counters, with/without edgeCritRule), $1 blocks"
INSTX="keepWithin=2.3 jumpAtReadyOnlyFleeing=true edgeCritRule=false" CANDX="keepWithin=2.3 jumpAtReadyOnlyFleeing=true edgeCritRule=true" \
  bash finalpair2.sh /d/swordai/run/kept-v21-final-1950 EDGE "$1"
echo "=== $(date +%H:%M:%S) edge crit check done"
