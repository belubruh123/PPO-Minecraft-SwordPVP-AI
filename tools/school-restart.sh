#!/usr/bin/env bash
# school-restart.sh [command]: stop the dev client, build, start it again and send the school command (default "swordai school").
CMD=${1:-swordai school}
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
L=/d/swordai/run/school-client-live.log
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { (\$_.Name -in 'cmd.exe','java.exe') -and \$_.CommandLine -match 'start-school\.cmd|start-dev\.cmd|GradleWrapperMain|GradleDaemon|fabric\.dli\.' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
sleep 3
# Skip slow Gradle build on unattended crash restarts when compiled classes & Loom launch jars are already intact and up to date.
CORE_JAR=$(ls -t /d/swordai/core/build/libs/swordai-core-*.jar 2>/dev/null | head -1)
NEED_BUILD=0
[ -z "$CORE_JAR" ] || [ ! -d /d/swordai/build/classes/java/client ] && NEED_BUILD=1
[ "$NEED_BUILD" = "0" ] && [ -n "$(find /d/swordai/src /d/swordai/core/src -name '*.java' -newer "$CORE_JAR" -print -quit 2>/dev/null)" ] && NEED_BUILD=1
if [ "$NEED_BUILD" = "1" ]; then
  cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m $(bash "$S/carpet-pvp-switch.sh" pre) < NUL" < /dev/null > "$S/build-school.log" 2>&1
  echo "=== build: $(grep -a 'BUILD' "$S/build-school.log" | tail -1)"
  grep -aq "BUILD SUCCESSFUL" "$S/build-school.log" || { grep -a "error:" -A2 "$S/build-school.log" | head -20; exit 1; }
  V=$(ls -t /d/swordai/core/build/libs/swordai-core-*.jar | head -1 | xargs basename)
  sed -i "s/swordai-core-[0-9.]*\.jar/$V/" /d/swordai/build/loom-cache/argFiles/runClient
else
  echo "=== build: up-to-date (skipped)"
fi
# Self-heal Gradle 7-day cache GC if dev-launch-injector or fabric-log4j-util was pruned from ~/.gradle/caches.
DLI_JAR=$(tr ';' '\n' < /d/swordai/build/loom-cache/argFiles/runClient 2>/dev/null | grep -E 'dev-launch-injector|fabric-log4j-util')
MISSING_DLI=0
for j in $DLI_JAR; do
  jp=$(cygpath -u "$j" 2>/dev/null || echo "$j")
  [ ! -f "$jp" ] && MISSING_DLI=1
done
if [ "$MISSING_DLI" = "1" ]; then
  echo "=== restoring pruned Loom launch jars via Gradle"
  printf 'allprojects { afterEvaluate { tasks.register("resolveLoomDev") { doLast { configurations.findByName("loomDevelopmentDependencies")?.resolve() } } } }\n' > /d/swordai/run/resolve-loom.gradle
  cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai resolveLoomDev --init-script D:\swordai\run\resolve-loom.gradle --no-daemon -Dorg.gradle.jvmargs=-Xmx1024m < NUL" < /dev/null >> "$S/build-school.log" 2>&1
  rm -f /d/swordai/run/resolve-loom.gradle
fi
echo "=== Carpet PvP (run/carpet-pvp.on): $(bash "$S/carpet-pvp-switch.sh" post)"
[ -f $L ] && cp $L /d/swordai/run/school-client-$(date +%m%d-%H%M).log
: > $L
# The client in a console of its own (7 Nov): started into the caller's console it kept the watchdog task's run open, and
# Task Scheduler then skipped every later run as "already running". A headless console like the task's own, so the game
# window shows as before.
powershell -NoProfile -Command "Start-Process -FilePath conhost.exe -ArgumentList '--headless','cmd.exe','/c','D:\swordai\run\start-school.cmd'"
for i in $(seq 1 120); do grep -aq "joined the game" $L 2>/dev/null && break; sleep 5; done
grep -aq "joined the game" $L || { echo "=== $(date +%H:%M:%S) world never loaded"; exit 1; }
powershell -NoProfile -ExecutionPolicy Bypass -File D:/swordai/tools/training-window.ps1 hide   # off the taskbar
sleep 10
printf '%s\r\n' "$CMD" > /d/swordai/run/swordai/inbox.txt
sleep 15
echo "=== $(date +%H:%M:%S) sent: $(grep -a "inbox: /$CMD" $L | tail -1 | cut -c1-80)"
grep -a "\[SwordAI\]" $L | tail -3 | cut -c1-200
