#!/usr/bin/env bash
# afterplay2.sh FROM: 22 Oct 23:02, afterplay.sh plus: the user's recorded fights against the one that stands, "plain" and
# "people" are set aside in run/swordai/demos-aside (the user, 23:00: only the fights where it attacks them are to be
# used), and the jar with /swordai demo spacer leaving the stander out (and a "stander" kind) is built and put into
# their profile (install3.ps1, same network, backup) while their game is closed; then training starts again.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
prof="/c/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI"
running() { powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -match 'DRL AI' } | Measure-Object).Count" | tr -d '\r'; }
until [ "$(running)" != "0" ]; do sleep 30; done
echo "=== $(date +%H:%M:%S) your game is running"
while [ "$(running)" != "0" ]; do sleep 60; done
echo "=== $(date +%H:%M:%S) your game closed"
mkdir -p /d/swordai/run/swordai/demos /d/swordai/run/swordai/demos-aside
cp -n "$prof/swordai/demos/"*.bin.gz /d/swordai/run/swordai/demos/ 2>/dev/null
for f in /d/swordai/run/swordai/demos/*stands* /d/swordai/run/swordai/demos/*plain* /d/swordai/run/swordai/demos/*people*; do
  [ -f "$f" ] && mv "$f" /d/swordai/run/swordai/demos-aside/
done
echo "=== recorded fights to learn from: $(ls /d/swordai/run/swordai/demos | wc -l), set aside: $(ls /d/swordai/run/swordai/demos-aside | wc -l)"
cmd //c "set JAVA_HOME=C:\Users\jerem\.gradle\jdks\eclipse_adoptium-25-amd64-windows.2&& D:\swordai\gradlew.bat -p D:\swordai build -x test --console=plain" > build-afterplay.log 2>&1
echo "=== build: $(grep -a 'BUILD' build-afterplay.log | tail -1)"
if grep -aq "BUILD SUCCESSFUL" build-afterplay.log; then
  powershell -NoProfile -ExecutionPolicy Bypass -File install3.ps1 -Net 'D:\swordai\run\kept-v21-final-1950' -Version '0.15.4' > install-afterplay.log 2>&1
  echo "=== install: $(grep -a 'jar  same\|net  same\|running' install-afterplay.log | tr '\r\n' '  ')"
fi
cp client.log client-run129-before-play.log 2>/dev/null
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
sleep 20
bash startT4b.sh "$from"
