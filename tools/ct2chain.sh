#!/usr/bin/env bash
# ct2chain.sh: 23 Oct 16:50. ct (clash jump + hold + instant punish) lost the drill by 7.2 a fight (se 0.4): the clash took
# off 1-2 ticks before the crit landed, hit in the air it flew out of reach, 40% 0/21. Now: clash off; the hold keeps the meet
# rule's walk in and drops only its swing; and the fighter's own knockback 1-2 ticks late in the drill (KnockbackLag.setAgent,
# both sides). Drill alone (jumpInShare=1), installed network, 4 min each, against the rules off:
# (1) hold + instant punish (label ct2h); (2) hold only (label ct2o).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
INST=/d/swordai/run/kept-v21-final-1950
J=/c/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin
OFF="clashJumpRule=false holdOnFallingJumper=false instantPunish=false"
echo "=== $(date +%H:%M:%S) closing the client for the build"
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 6
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'launch\.cfg|start-dev\.cmd|GradleWrapperMain|GradleDaemon|net\.fabricmc' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
sleep 3
H=$(pwd)/jc; HW=$(cygpath -w $H)
(cd /d/swordai/core/src/main/java && find . -name '*.java' > $H/allsrc.txt)
rm -rf $H/out2 $H/test2
(cd /d/swordai/core/src/main/java && "$J/javac" -nowarn -encoding UTF-8 -d "$HW\out2" -cp "$(cat $H/cp.txt)" @$H/allsrc.txt 2>&1 | grep -v "^Note:" | head -8)
(cd /d/swordai/core/src/test/java && "$J/javac" -nowarn -encoding UTF-8 -d "$HW\test2" -cp "$HW\out2;$(cat $H/cp.txt)" dev/swordai/core/rl/CritTradeTest.java dev/swordai/core/rl/JumpInTest.java dev/swordai/core/rl/AnswerRuleTest.java dev/swordai/core/rl/FighterTest.java 2>&1 | grep -v "^Note:" | head -8)
(cd $H && "$J/java" -cp "test2;out2;runner;$(cat cp.txt)" RunTests dev.swordai.core.rl.CritTradeTest dev.swordai.core.rl.JumpInTest dev.swordai.core.rl.AnswerRuleTest dev.swordai.core.rl.FighterTest 2>&1 | grep -E "tests (successful|failed)|MethodSource|=>" | head -16)
cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > build-ct2.log 2>&1
echo "=== build: $(grep -a 'BUILD' build-ct2.log | tail -1)"
grep -aq "BUILD SUCCESSFUL" build-ct2.log || { grep -a "error:" -A2 build-ct2.log | head -20; exit 1; }
cp client.log client-run-ct.log
: > client.log
python setcfg.py $OFF jumpInShare=0 > /dev/null
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
sleep 10
echo "=== $(date +%H:%M:%S) drill: hold + instant punish (A) vs rules off (B), 4 min"
BX="$OFF" bash pairrun.sh $INST $INST ct2h sp 240 jumpInShare=1 holdOnFallingJumper=true instantPunish=true clashJumpRule=false
python drillpf.py client.log
echo "=== $(date +%H:%M:%S) drill: hold only (A) vs rules off (B), 4 min"
BX="$OFF" bash pairrun.sh $INST $INST ct2o sp 240 jumpInShare=1 holdOnFallingJumper=true instantPunish=false clashJumpRule=false
python drillpf.py client.log
python setcfg.py $OFF jumpInShare=0 > /dev/null
echo "=== $(date +%H:%M:%S) ct2 test done"
