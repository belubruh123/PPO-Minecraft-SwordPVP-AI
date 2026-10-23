#!/usr/bin/env bash
# diagT9.sh T9NET: 23 Oct 20:40. T9 (restarted) at 20:03 was spacers -38 (se 16), stander -58 (se 17) against the installed
# network, where T8's checks were level. Rules or training? Spacers 10 min each, paired:
# (d1) T8-1534 with the T9 rules (A) vs T8-1534 without them (B): what the rules alone do on the same network;
# (d2) T9NET vs T8-1534, both with the T9 rules: what the 40 min of T9 training did.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
T9=$1
T8=/d/swordai/run/kept-v24-T8-1534
CANDX="tacticModel=true tacticOnEvents=true opponentReader=true keepWithin=2.3 jumpAtReadyOnlyFleeing=true critBridge=true rhythmNet=true holdOnFallingJumper=true holdOnRising=false instantPunish=true"
echo "=== $(date +%H:%M:%S) d1: T8 with the T9 rules vs T8 without, spacers 10 min"
BX="holdOnFallingJumper=false instantPunish=false" SEED=2026102370 bash pairrun.sh $T8 $T8 d1 sp 600 $CANDX
echo "=== $(date +%H:%M:%S) d2: T9 vs T8, both with the rules, spacers 10 min"
BX="" SEED=2026102371 bash pairrun.sh $T9 $T8 d2 sp 600 $CANDX
echo "=== $(date +%H:%M:%S) diag done"
