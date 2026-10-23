#!/usr/bin/env bash
# ct5chain.sh: 23 Oct 21:00. The crit trade rules fire only on a real jump-in (coming at the fighter at 0.05 a tick or more at
# any point of the jump; CritTrade.IN_TOWARD), after d1 found them costing -51 against the stander's jump resets. Build, then on
# the INSTALLED network, rules (hold from the top + instant punish) vs off: spacers 10 min, mix 12 min, drill 5 min.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
INST=/d/swordai/run/kept-v21-final-1950
J=/c/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin
OFF="clashJumpRule=false holdOnFallingJumper=false instantPunish=false"
ON="holdOnFallingJumper=true holdOnRising=false instantPunish=true clashJumpRule=false"
until grep -q "diag done" diag-T9.out; do sleep 10; done
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 6
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'launch\.cfg|start-dev\.cmd|GradleWrapperMain|GradleDaemon|net\.fabricmc' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
sleep 3
H=$(pwd)/jc; HW=$(cygpath -w $H)
(cd /d/swordai/core/src/main/java && find . -name '*.java' > $H/allsrc.txt)
rm -rf $H/out2 $H/test2
(cd /d/swordai/core/src/main/java && "$J/javac" -nowarn -encoding UTF-8 -d "$HW\out2" -cp "$(cat $H/cp.txt)" @$H/allsrc.txt 2>&1 | grep -v "^Note:" | head -8)
(cd /d/swordai/core/src/test/java && "$J/javac" -nowarn -encoding UTF-8 -d "$HW\test2" -cp "$HW\out2;$(cat $H/cp.txt)" dev/swordai/core/rl/CritTradeTest.java dev/swordai/core/rl/FighterTest.java 2>&1 | grep -v "^Note:" | head -8)
(cd $H && "$J/java" -cp "test2;out2;runner;$(cat cp.txt)" RunTests dev.swordai.core.rl.CritTradeTest dev.swordai.core.rl.FighterTest 2>&1 | grep -E "tests (successful|failed)|MethodSource|=>" | head -8)
cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > build-ct5.log 2>&1
echo "=== build: $(grep -a 'BUILD' build-ct5.log | tail -1)"
grep -aq "BUILD SUCCESSFUL" build-ct5.log || { grep -a "error:" -A2 build-ct5.log | head -20; exit 1; }
cp client.log client-run-diag.log
: > client.log
python setcfg.py $OFF jumpInShare=0 > /dev/null
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
sleep 10
echo "=== $(date +%H:%M:%S) installed net, rules on vs off: spacers 10 min"
BX="$OFF" SEED=2026102380 bash pairrun.sh $INST $INST ct5 sp 600 jumpInShare=0 $ON
echo "=== $(date +%H:%M:%S) mix 12 min"
BX="$OFF" SEED=2026102381 bash pairrun.sh $INST $INST ct5 mix 720 jumpInShare=0 $ON
echo "=== $(date +%H:%M:%S) drill 5 min"
BX="$OFF" SEED=2026102382 bash pairrun.sh $INST $INST ct5d sp 300 jumpInShare=1 $ON
python drillpf.py client.log
python setcfg.py $OFF jumpInShare=0 > /dev/null
echo "=== $(date +%H:%M:%S) ct5 done"
