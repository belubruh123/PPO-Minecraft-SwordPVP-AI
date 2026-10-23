#!/usr/bin/env bash
# watch.sh MINUTES: waits MINUTES, then sums that stretch of training and flags anything broken (sanity.py).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
from=$(date +%H:%M:%S)
sleep $(( $1 * 60 ))
to=$(date +%H:%M:%S)
# Across midnight the log's clock starts over: take the part after it.
[[ "$from" > "$to" ]] && from=00:00:00
python progress.py client.log "$from" "$to" | tail -1 | cut -c1-600
python sanity.py client.log "$from" "$to"
tasklist //FI "IMAGENAME eq java.exe" 2>/dev/null | grep -c java | sed 's/^/java processes: /'
