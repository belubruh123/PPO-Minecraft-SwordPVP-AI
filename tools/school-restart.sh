#!/usr/bin/env bash
# school-restart.sh [command]: stop the dev client, build, start it again and send the school command (default "swordai school").
CMD=${1:-swordai school}
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
L=/d/swordai/run/school-client.log
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'start-school\.cmd|start-dev\.cmd|GradleWrapperMain|GradleDaemon|net\.fabricmc' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
sleep 3
cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > "$S/build-school.log" 2>&1
echo "=== build: $(grep -a 'BUILD' "$S/build-school.log" | tail -1)"
grep -aq "BUILD SUCCESSFUL" "$S/build-school.log" || { grep -a "error:" -A2 "$S/build-school.log" | head -20; exit 1; }
V=$(ls -t /d/swordai/core/build/libs/swordai-core-*.jar | head -1 | xargs basename)
sed -i "s/swordai-core-[0-9.]*\.jar/$V/" /d/swordai/build/loom-cache/argFiles/runClient
[ -f $L ] && cp $L /d/swordai/run/school-client-$(date +%m%d-%H%M).log
: > $L
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-school.cmd" > /dev/null 2>&1 &
disown
for i in $(seq 1 120); do grep -aq "joined the game" $L 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" $L || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
sleep 10
printf '%s\r\n' "$CMD" > /d/swordai/run/swordai/inbox.txt
sleep 15
echo "=== $(date +%H:%M:%S) sent: $(grep -a "inbox: /$CMD" $L | tail -1 | cut -c1-80)"
grep -a "\[SwordAI\]" $L | tail -3 | cut -c1-200
