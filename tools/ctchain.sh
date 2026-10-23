#!/usr/bin/env bash
# ctchain.sh: 23 Oct 16:40. The crit trade (the user, 23 Oct: friends beat the installed network every time by jumping in
# with the sword ready; its hit to knock them away does not stop their crit). Build with: the clash jump (JumpIn.CLASH, crit
# for crit, the main answer), the hold rule (CritTrade.holds: no swing at an opponent coming in through the air, knock-away
# only when certain) and the instant punish crit (CritTrade.punish), and the jump-in drill (KIND_JUMPIN, Config.jumpInShare).
# Then, on the installed network:
# (1) the drill alone (jumpInShare=1), rules ON (A) vs OFF (B), 8 min (label ct);
# (2) the mix, rules ON vs OFF, 14 min: that the rules cost nothing elsewhere.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
T=/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/tasks
INST=/d/swordai/run/kept-v21-final-1950
J=/c/Users/jerem/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2/bin
ON="clashJumpRule=true holdOnFallingJumper=true instantPunish=true"
OFF="clashJumpRule=false holdOnFallingJumper=false instantPunish=false"
until grep -q "combo jump v2 test done" $T/b6lnk4gma.output; do sleep 10; done
echo "=== $(date +%H:%M:%S) closing the client for the crit trade build"
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'launch\.cfg|start-dev\.cmd|GradleWrapperMain|GradleDaemon|net\.fabricmc' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
sleep 3
H=$(pwd)/jc; HW=$(cygpath -w $H)
(cd /d/swordai/core/src/main/java && find . -name '*.java' > $H/allsrc.txt)
rm -rf $H/out2 $H/test2
(cd /d/swordai/core/src/main/java && "$J/javac" -nowarn -encoding UTF-8 -d "$HW\out2" -cp "$(cat $H/cp.txt)" @$H/allsrc.txt 2>&1 | grep -v "^Note:" | head -8)
(cd /d/swordai/core/src/test/java && "$J/javac" -nowarn -encoding UTF-8 -d "$HW\test2" -cp "$HW\out2;$(cat $H/cp.txt)" dev/swordai/core/rl/CritTradeTest.java dev/swordai/core/rl/JumpInTest.java dev/swordai/core/rl/AnswerRuleTest.java dev/swordai/core/rl/FighterTest.java 2>&1 | grep -v "^Note:" | head -8)
(cd $H && "$J/java" -cp "test2;out2;runner;$(cat cp.txt)" RunTests dev.swordai.core.rl.CritTradeTest dev.swordai.core.rl.JumpInTest dev.swordai.core.rl.AnswerRuleTest dev.swordai.core.rl.FighterTest 2>&1 | grep -E "tests (successful|failed)|MethodSource|=>" | head -16)
cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > build-ct.log 2>&1
echo "=== build: $(grep -a 'BUILD' build-ct.log | tail -1)"
grep -aq "BUILD SUCCESSFUL" build-ct.log || { grep -a "error:" -A2 build-ct.log | head -20; exit 1; }
cp client.log client-run-cj2.log
: > client.log
python setcfg.py $OFF jumpInShare=0 > /dev/null
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
for i in $(seq 1 120); do grep -aq "joined the game" client.log 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" client.log || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
sleep 10
echo "=== $(date +%H:%M:%S) jump-in drill alone: rules ON (A) vs OFF (B), 8 min"
BX="$OFF" bash pairrun.sh $INST $INST ct sp 480 jumpInShare=1 $ON
grep -a "jump-in drill and crit trades" client.log | tail -4
echo "=== $(date +%H:%M:%S) mix: rules ON vs OFF, 14 min"
BX="$OFF" bash pairrun.sh $INST $INST ct mix 840 jumpInShare=0 $ON
grep -a "jump-in drill and crit trades" client.log | tail -3
python setcfg.py $OFF jumpInShare=0 > /dev/null
echo "=== $(date +%H:%M:%S) crit trade test done"
