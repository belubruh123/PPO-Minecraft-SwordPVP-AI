#!/usr/bin/env bash
# mirror-long.sh: pause the training loop, run a 132-pair mirror check of run 18's kept network, resume the loop.
S=/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad
C=school; C="${C}cycle"
SCH=/d/swordai/run/swordai/school
touch /d/swordai/run/school-cycle.stop
for i in $(seq 1 90); do pgrep -f "$C" >/dev/null 2>&1 || ps -ef | grep -a "$C" | grep -qv grep || break; sleep 2; done
echo "loop paused $(date +%H:%M:%S)"
touch $SCH/spar-check
PAIRS=${PAIRS:-132} bash $S/net-check.sh "${1:-/d/swordai/run/best-run18/checkpoint.bin}" "${2:-LONG MIRROR CHECK}"
cp -p $S/check-part.log $S/${3:-mirror-long}-part.log
rm -f $SCH/spar-check /d/swordai/run/school-cycle.stop
nohup bash $S/$C.sh >/dev/null 2>&1 &
echo "loop restarted $(date +%H:%M:%S)"
