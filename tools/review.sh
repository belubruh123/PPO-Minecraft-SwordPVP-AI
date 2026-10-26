#!/usr/bin/env bash
# review.sh [SINCE HH:MM]: the ten-minute review in a few lines.
cd /d/swordai
L=run/school-client.log
echo "$(date +%H:%M:%S) check running: $(tail -40 $L | grep -ac 'school pair')"
grep -a "school update" $L | tail -6 | tr '|' '\n' | grep -oE "school update [0-9]+|\(body [0-9.]+\)|with the crosshair on them \([0-9]+%\)|a third after [0-9]+ \([0-9]+%\)|turned to find them on [0-9]+|K2 broken [0-9]+ |sparring the installed model: [0-9]+ fights, knockouts [0-9]+-[0-9]+|net [-0-9.]+ a fight$" | paste -sd' ' | sed 's/school update/\nupd/g; s/with the crosshair on them/aim/g; s/turned to find them on/lost view/g; s/sparring the installed model:/spar/g' | cut -c1-200
SINCE=${1:-$(date -d '-10 min' +%H:%M)}
grep -a "school fight " $L | awk -F'[][]' -v s="$SINCE" '$2>=s' | grep -oE "dealt [0-9.]+, taken [0-9.]+, ticks [0-9]+" | awk -v s="$SINCE" '{d+=$2; t+=$4; k+=$6; n++} END {if (k) printf "since %s: %d fights, dealt %.0f taken %.0f a minute (net %+.0f)\n", s, n, d/(k/1200), t/(k/1200), (d-t)/(k/1200)}'
timeout 60 C:/Python38/python.exe tools/tech-review.py 8 2>&1 | awk '{print $1, $2, $NF}' | paste -sd' ' | cut -c1-700
C:/Python38/python.exe /c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad/scenesum.py
grep -a "school fight " $L | awk -F'[][]' -v s="$SINCE" '$2>=s' | awk '{spar=($0 ~ /sparring/); bot=$0; sub(/.*school fight /,"",bot); sub(/[ :(].*/,"",bot); crit=(bot ~ /critter|crit-breaker/); if (match($0,/dealt [0-9.]+, taken [0-9.]+, ticks [0-9]+/)) { y=substr($0,RSTART,RLENGTH); gsub(/,/,"",y); split(y,b," "); kind = spar ? "installed" : crit ? "crit bots" : "other bots"; d[kind]+=b[2]; tk[kind]+=b[4]; k[kind]+=b[6]; n[kind]++ } } END { for (x in n) printf "  %-10s %3d fights net %+4.0f a minute (%.0f/%.0f)\n", x, n[x], (d[x]-tk[x])/(k[x]/1200), d[x]/(k[x]/1200), tk[x]/(k[x]/1200) }'
