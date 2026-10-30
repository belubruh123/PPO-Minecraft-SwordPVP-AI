#!/usr/bin/env bash
# school-redeploy.sh: stop the hourly loop and the client, run the school's core tests, rebuild, restart training and the loop.
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
touch /d/swordai/run/school-cycle.stop
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe','bash.exe') -and \$_.CommandLine -match 'start-school\.cmd|net\.fabricmc|schoolcycle\.sh' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
# CLEAR_SCENES=1: once the client has stopped, the kept scenes (scenes.txt, scenes-mastered.txt) are put aside and it starts with none.
if [ -n "$CLEAR_SCENES" ]; then
  for f in scenes.txt scenes-mastered.txt; do [ -f /d/swordai/run/swordai/school/$f ] && mv /d/swordai/run/swordai/school/$f /d/swordai/run/swordai/school/$f.old-$(date +%H%M); done
  echo "scenes put aside"
fi
# RESET_JUMPIN=1: once the client has stopped, the jump-in measures in focus.txt (the bots', the installed AI's and the
# jump-in scenes') start again: for a change of what counts as countered (26 Oct: only its own crit, a punish crit).
if [ -n "$RESET_JUMPIN" ]; then
  python - <<'PYEOF'
p = "D:/swordai/run/swordai/school/focus.txt"
v = open(p).read().split()
# focus, furthest, then jumpInRoll, comboRoll, crit scenes, jump-in scenes, combo scenes, jumpInSparRoll
for i in (2, 5, 7):
    if i < len(v):
        v[i] = "-"
open(p, "w").write(" ".join(v))
print("jump-in measures start again")
PYEOF
fi
# RESET_PUNISH=1: the same for the punish crit's bar (punish chances critted, the 9th field) after a change of what a chance is.
if [ -n "$RESET_PUNISH" ]; then
  python - <<'PYEOF'
p = "D:/swordai/run/swordai/school/focus.txt"
v = open(p).read().split()
if len(v) > 8:
    v[8] = "-"
open(p, "w").write(" ".join(v))
print("punish measure starts again")
PYEOF
fi
# RESET_FIELDS="i j": once the client has stopped, these fields of focus.txt (0 focus, 1 furthest, then the rolling measures
# in the order of SchoolSession#rollings) start again: for a change of what a measure counts (27 Oct: 9 = punish scenes, now
# the forced punish crit drill).
if [ -n "$RESET_FIELDS" ]; then
  RESET_FIELDS="$RESET_FIELDS" python - <<'PYEOF'
import os
p = "D:/swordai/run/swordai/school/focus.txt"
v = open(p).read().split()
for i in map(int, os.environ["RESET_FIELDS"].split()):
    if i < len(v):
        v[i] = "-"
open(p, "w").write(" ".join(v))
print("focus fields reset:", os.environ["RESET_FIELDS"])
PYEOF
fi
# GOTO_STAGE=N: once the client has saved and stopped, the school goes to stage N (0 = T-A ... 4 = T-E) from its start.
if [ -n "$GOTO_STAGE" ]; then
  sed -i "s/^stage=.*/stage=$GOTO_STAGE/; s/^stageSteps=.*/stageSteps=0/" /d/swordai/run/swordai/school/stages.properties
  grep -n "^stage=\|^stageSteps=" /d/swordai/run/swordai/school/stages.properties
fi
# FADE_FROM=N: the body copying's fade (SchoolStages#FADE_STEPS) counts from school step N; FADE_FROM=new starts it again.
if [ -n "$FADE_FROM" ]; then
  sed -i '/^fadeFrom=/d' /d/swordai/run/swordai/school/stages.properties
  [ "$FADE_FROM" != new ] && echo "fadeFrom=$FADE_FROM" >> /d/swordai/run/swordai/school/stages.properties
  grep -n "^fadeFrom=\|^schoolSteps=" /d/swordai/run/swordai/school/stages.properties
fi
# START_NET=FILE: once the client has saved and stopped, the school network becomes FILE (the one it had is kept).
if [ -n "$START_NET" ]; then
  cp -p /d/swordai/run/swordai/school/checkpoint.bin /d/swordai/run/swordai/school/training-kept-$(date +%H%M).bin
  cp -p "$START_NET" /d/swordai/run/swordai/school/checkpoint.bin && echo "school network now $(basename "$START_NET")"
fi
cd /d/swordai
export JAVA_HOME="C:/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2"
./gradlew.bat :core:test --tests 'dev.swordai.core.school.*' --tests 'dev.swordai.core.pvp.*' -q > "$S/test-school.log" 2>&1
python - <<'EOF'
import re, glob, sys
bad = 0
for f in glob.glob('D:/swordai/core/build/test-results/test/TEST-dev.swordai.core.*.xml'):
    if 'school' not in f and 'pvp' not in f:
        continue
    s = open(f, encoding='utf8').read()
    r = re.findall(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', s)[0]
    print('test', f.split('TEST-')[1], r)
    bad += int(r[2]) + int(r[3])
    for m in re.finditer(r'<failure message="([^"]*)"', s):
        print('FAIL', m.group(1)[:300])
sys.exit(1 if bad else 0)
EOF
[ $? -ne 0 ] && { echo "=== tests failed, nothing restarted"; exit 1; }
bash "$S/school-restart.sh" 2>&1 | tail -2
rm -f /d/swordai/run/school-cycle.stop
cd /d/swordai/run && nohup bash "$S/schoolcycle.sh" > /d/swordai/run/school-cycle.out 2>&1 &
disown
echo "=== cycle restarted $(date +%H:%M)"
