#!/usr/bin/env bash
# ct4chain.sh: 23 Oct 17:27. ct3 (hold + punish with ground wait) won the drill +3.16 a fight (se 0.34) but the mix -43 a
# minute (se 15). Which half costs in the mix: (1) hold only vs off, (2) instant punish only vs off; mix 12 min each.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
INST=/d/swordai/run/kept-v21-final-1950
OFF="clashJumpRule=false holdOnFallingJumper=false instantPunish=false"
J=/c/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin
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
cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > build-ct4.log 2>&1
echo "=== build: $(grep -a 'BUILD' build-ct4.log | tail -1)"
grep -aq "BUILD SUCCESSFUL" build-ct4.log || { grep -a "error:" -A2 build-ct4.log | head -20; exit 1; }
cp client.log client-run-ct3.log
: > client.log
python setcfg.py $OFF jumpInShare=0 > /dev/null
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
sleep 10
echo "=== $(date +%H:%M:%S) mix: hold only (A) vs off (B), 12 min"
BX="$OFF" bash pairrun.sh $INST $INST ct4h mix 720 jumpInShare=0 holdOnFallingJumper=true instantPunish=false clashJumpRule=false
echo "=== $(date +%H:%M:%S) mix: instant punish only (A) vs off (B), 12 min"
BX="$OFF" bash pairrun.sh $INST $INST ct4p mix 720 jumpInShare=0 instantPunish=true clashJumpRule=false holdOnFallingJumper=false
echo "=== $(date +%H:%M:%S) mix: hold from the top of the jump only (A) vs off (B), 12 min"
BX="$OFF" bash pairrun.sh $INST $INST ct4r mix 720 jumpInShare=0 holdOnFallingJumper=true holdOnRising=false instantPunish=false clashJumpRule=false
python setcfg.py $OFF jumpInShare=0 holdOnRising=true > /dev/null
echo "=== $(date +%H:%M:%S) ct4 test done"
