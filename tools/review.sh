#!/usr/bin/env bash
# review.sh: everything the 30-minute review reads, in one short printout. Read only. Run with: bash tools/review.sh
cd /d/swordai/run || exit 1
date
ls -la --time-style=+%H:%M:%S school-client-live.log | awk '{print "log last written",$6}'
echo "loops $(ps -ef | grep -c '[s]choolcycle')  java $(tasklist 2>/dev/null | grep -c -i java.exe)  (want 1 and 1)"
echo "user's Minecraft (javaw) $(tasklist 2>/dev/null | grep -c -i javaw.exe)  (above 0: training numbers read about 30% worse, HANDOFF section 1)"
echo "pair lines $(tail -40 school-client-live.log | grep -c 'school pair')  (above 0: a paired check is playing, no redeploy)"
U=$(grep -E "school update" school-client-live.log | tail -8)
L=$(echo "$U" | tail -1)
echo "$L" | cut -c1-90
echo "$U" | grep -oE "swings: [0-9]+ swings, [0-9]+ with the crosshair on them \([0-9]+%\), [0-9]+ hits landed \([0-9]+%\)" | sed 's/ with the crosshair on them//' | tail -4
echo "$L" | grep -oE "aim on [0-9]+, turned to find them on [0-9]+ \(off the screen [0-9]+\)"
echo "$L" | grep -oE "K2 broken [0-9]+ \(" | head -1
echo "$L" | grep -oE "a third after [0-9]+ \([0-9]+%\)" | head -1
echo "$L" | grep -oE "punish crit chances critted[^,|]*, last [0-9]+% of [0-9]+|told the punish crit and landed it, last [0-9]+% of [0-9]+"
grep -E "damage a minute" school-client-live.log | grep -v CHAT | tail -2 | grep -oE "damage a minute [0-9]+ dealt, [0-9]+ taken" | paste -sd' '
echo "--- take-over (run 30): the rules' keys, and the fights with/without the rules since the last redeploy"
echo "$L" | grep -oE "take-over: rules' keys ce [0-9.]+ \(smoothed\), agree [0-9]+%(, pull [0-9.]+)?; share of full fights without the rules [0-9]+%"
C:/Python38/python.exe D:/swordai/tools/takeover.py D:/swordai/run/school-client-live.log 2>&1 | tail -6
echo "--- techniques"
C:/Python38/python.exe D:/swordai/tools/tech-review.py 8 2>&1 | cut -c1-100 | grep -E "RULE|80%\+|^B1|^K2|^C4|^T4 "
echo "--- last check or screen"
grep -E "^=== " school-checks.log | tail -1 | cut -c1-110
awk '/^=== 1[0-2]-[0-9]+ /{b=""} {b=b"\n"$0} END{print b}' school-checks.log | grep -E "^ALL|^fight-timer|A swings|B swings|A jumping|B jumping" | cut -c1-130
ls -t swordai/school/checked-*.bin | head -1
echo "--- live A/B pool in the user's duels (empty until two arms have played)"
C:/Python38/python.exe D:/swordai/tools/duels.py 2>&1 | sed -n '/^arms of the live/,$p' | cut -c1-130
echo "--- OPEN PROBLEMS (D:/swordai/PROBLEMS.md, the user's checklist: never remove an item until real duels show it solved)"
sed -n '/^## OPEN/,/^## SOLVED/p' /d/swordai/PROBLEMS.md | grep -E "^[0-9]+\. " | cut -c1-160
