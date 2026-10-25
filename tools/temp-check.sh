#!/usr/bin/env bash
# temp-check.sh NET TEMP LABEL OUTNAME: pause the training loop, check NET with the school side picking at temperature TEMP
# (scripted bots unless spar-check is set), put the temperature back to 1.0, resume the loop.
S=/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad
C=school; C="${C}cycle"
CFG=/d/swordai/run/swordai/config.json
touch /d/swordai/run/school-cycle.stop
for i in $(seq 1 90); do ps -ef | grep -a "$C" | grep -qv grep || break; sleep 2; done
echo "loop paused $(date +%H:%M:%S)"
sed -i -E "s/\"playTemperature\": [0-9.]+/\"playTemperature\": $2/" $CFG; grep playTemperature $CFG
PAIRS=${PAIRS:-133} bash $S/net-check.sh "$1" "$3"
cp -p $S/check-part.log $S/$4-part.log
sed -i -E 's/"playTemperature": [0-9.]+/"playTemperature": 1.0/' $CFG; grep playTemperature $CFG
rm -f /d/swordai/run/school-cycle.stop
nohup bash $S/$C.sh >/dev/null 2>&1 &
echo "loop restarted $(date +%H:%M:%S)"
