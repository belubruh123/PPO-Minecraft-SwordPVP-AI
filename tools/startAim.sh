#!/usr/bin/env bash
# startAim.sh BLOCKS: 22 Oct. Level aim (Config.aimLevel, AimRule.level) plus keepWithin 2.6 instead of 2.3: the INSTALLED network with
# the two coach counters on in both arms (INSTX), and level aim on top in the candidate arm (CANDX); same fight sets, order
# alternating (finalpair2.sh).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; grep -aq "^exit code" client.log && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; tail -20 client.log; exit 1; }
echo "=== $(date +%H:%M:%S) aim+keep2.6 check (installed net + counters keep 2.3 vs level aim + keep 2.6), $1 blocks"
INSTX="keepWithin=2.3 jumpAtReadyOnlyFleeing=true edgeCritRule=false aimLevel=false" CANDX="keepWithin=2.6 jumpAtReadyOnlyFleeing=true edgeCritRule=false aimLevel=true" \
  bash finalpair2.sh /d/swordai/run/kept-v21-final-1950 AIM "$1"
echo "=== $(date +%H:%M:%S) aim check done"
