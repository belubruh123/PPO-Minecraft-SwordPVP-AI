#!/usr/bin/env bash
# afterplay.sh FROM: 22 Oct 22:50. The user records their own fights (/swordai demo) in their own profile; the handheld
# cannot run their game and the training client at once. Waits for their game to start and then to close, copies their
# recorded fights into the dev run (run/swordai/demos), then starts the training client again and T4 from FROM with the
# loop that waits for recordings (startT4b.sh -> hourlycheck3.sh).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$1
prof="/c/Users/jerem/AppData/Roaming/ModrinthApp/profiles/DRL AI"
running() { powershell -NoProfile -Command "(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -match 'DRL AI' } | Measure-Object).Count" | tr -d '\r'; }
until [ "$(running)" != "0" ]; do sleep 30; done
echo "=== $(date +%H:%M:%S) your game is running"
while [ "$(running)" != "0" ]; do sleep 60; done
echo "=== $(date +%H:%M:%S) your game closed"
mkdir -p /d/swordai/run/swordai/demos
cp -n "$prof/swordai/demos/"*.bin.gz /d/swordai/run/swordai/demos/ 2>/dev/null
echo "=== recorded fights in the dev run: $(ls /d/swordai/run/swordai/demos 2>/dev/null | wc -l)"
cp client.log client-run129-before-play.log 2>/dev/null
cmd //c "C:\Users\jerem\AppData\Local\Temp\claude\D--swordai\a22e256c-5a71-40c7-b784-e1011c2e02b3\scratchpad\start-dev.cmd" &
sleep 20
bash startT4b.sh "$from"
