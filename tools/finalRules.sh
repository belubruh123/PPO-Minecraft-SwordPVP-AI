#!/usr/bin/env bash
# finalRules.sh: 23 Oct 20:45. The LONG FINAL CHECK of the INSTALLED network with the crit trade rules (instant punish crit,
# hold from the top of the jump) against itself without them: the first model to install if T9 training is what costs.
# the installed network with its own (B): spacers 20 min, mix 28 min, jump-in drill 8 min. Writes final-R-*.txt and a verdict
# line to final-R-verdict.txt. Training is NOT resumed (the caller decides). Waits while the user plays (demo.lock).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
net=/d/swordai/run/kept-v21-final-1950
INST=/d/swordai/run/kept-v21-final-1950
CANDX="holdOnFallingJumper=true holdOnRising=false instantPunish=true"
BX="keepWithin=0 jumpAtReadyOnlyFleeing=false holdOnFallingJumper=false instantPunish=false clashJumpRule=false comboJumpRule=false"
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
LOCK=/d/swordai/run/swordai/demo.lock
nodemo() { while [ -f $LOCK ]; do sleep 10; done; }
waitfor() { local left=$1; while [ $left -gt 0 ]; do sleep 10; [ -f $LOCK ] || left=$(( left - 10 )); done; }
run() {
  local out=$1 secs=$2 S E; shift 2
  nodemo
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" "pairedBase=$INST" "pairedBaseSettings=$BX" > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  cp "$net/lost-cases.json" /d/swordai/run/swordai/lost-cases.json 2>/dev/null
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S); waitfor "$secs"; E=$(date +%H:%M:%S)
  python pairsum.py client.log "$S" "$E" > "$out"
  echo "$out $S $E" >> pair-windows.txt
}
echo "=== $(date +%H:%M:%S) final check of $net"
run final-R-sp.txt 1200 $SPACER $CANDX
run final-R-mix.txt 1680 $MIX $CANDX
run final-R-ji.txt 480 $SPACER $CANDX jumpInShare=1
python drillpf.py client.log > final-R-ji-pf.txt
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
python setcfg.py pairedBase= pairedBaseSettings= evalSeed=0 > /dev/null
cp $net/lost-cases.json /d/swordai/run/swordai/lost-cases.json 2>/dev/null
python - <<'PYEOF' | tee final-R-verdict.txt
import re
def all_line(path):
    for line in open(path, encoding='utf-8'):
        if line.strip().startswith('ALL'):
            m = re.search(r'A-B net\s+([-+]?\d+) a minute \(se\s+(\d+)\)', line)
            return int(m.group(1)), int(m.group(2))
sp = all_line('final-R-sp.txt'); mix = all_line('final-R-mix.txt')
pf = open('final-R-ji-pf.txt', encoding='utf-8').read()
m = re.search(r'A-B net ([-+][\d.]+) a fight \(se ([\d.]+)\)', pf)
ji = (float(m.group(1)), float(m.group(2)))
drill_better = ji[0] > 2 * ji[1]
not_worse = lambda r: r[0] > -2 * r[1]
ok = drill_better and not_worse(sp) and not_worse(mix)
print('FINAL RULES on the installed net: jump-in drill A-B %+.2f a fight (se %.2f) %s | spacers A-B %+d a minute (se %d) %s | mix A-B %+d (se %d) %s => %s' % (
    ji[0], ji[1], 'BETTER' if drill_better else 'not better', sp[0], sp[1], 'ok' if not_worse(sp) else 'WORSE',
    mix[0], mix[1], 'ok' if not_worse(mix) else 'WORSE', 'INSTALL' if ok else 'DO NOT INSTALL'))
PYEOF
echo "=== $(date +%H:%M:%S) final check done"
