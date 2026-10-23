#!/usr/bin/env bash
# watch2.sh MINUTES: waits MINUTES, then the damage table for that stretch (report.py) and the health flags (sanity.py).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$(date +%H:%M:%S)
sleep $(( $1 * 60 ))
to=$(date +%H:%M:%S)
[[ "$from" > "$to" ]] && from=00:00:00
python report.py client.log "$from" "$to"
python sanity.py client.log "$from" "$to"
tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c java | sed 's/^/java processes: /'
