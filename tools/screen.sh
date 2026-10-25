#!/usr/bin/env bash
# screen.sh PAIRS NET:TEMP ...: pause the loop; check each NET (TEMP = a file in temps/ for the school side's play-temperature,
# or "1" for none) on scripted bots from pair 0; resume the loop. Results in run/school-checks.log, parts in screen-<n>-part.log.
S=/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad
C=school; C="${C}cycle"
SCH=/d/swordai/run/swordai/school
P="$1"; shift
touch /d/swordai/run/school-cycle.stop
for i in $(seq 1 90); do ps -ef | grep -a "$C" | grep -qv grep || break; sleep 2; done
echo "loop paused $(date +%H:%M:%S)"
n=0
for ITEM in "$@"; do
  n=$((n+1)); NET="${ITEM%%:*}"; T="${ITEM##*:}"
  rm -f $SCH/play-temperature
  [ "$T" != 1 ] && cp $S/temps/$T $SCH/play-temperature
  PAIRS=$P bash $S/net-check.sh "$NET" "SCREEN $n: $(basename $NET) at temperatures $T, scripted bots, $P pairs from pair 0"
  cp -p $S/check-part.log $S/screen-$n-part.log
  rm -f $SCH/play-temperature
  echo "screen $n done $(date +%H:%M:%S): $ITEM"
done
rm -f /d/swordai/run/school-cycle.stop
nohup bash $S/$C.sh >/dev/null 2>&1 &
echo "loop restarted $(date +%H:%M:%S)"
