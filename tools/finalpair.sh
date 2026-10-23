#!/usr/bin/env bash
# finalpair.sh NET LABEL: the final check before an install, paired (Config.evalSeed): NET and the INSTALLED network each
# on the same fights, frozen, first person: spacers only 10 min, the even mix 12 min, the 0.14.0 model only 8 min.
# Writes fp-LABEL-{cand,inst}-{sp,mix,old}.txt and a summary line to final-pair.log. Training is NOT restarted here.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
cand=$1; label=$2
INST=/d/swordai/run/kept-v21-final-1950
FROZEN=$(grep '^FROZEN=' hourlycheck.sh | sed 's/^FROZEN="\(.*\)"$/\1/')
SPACER=$(grep '^SPACER=' hourlycheck.sh | sed 's/^SPACER="\(.*\)"$/\1/')
MIX=$(grep '^MIX=' hourlycheck.sh | sed 's/^MIX="\(.*\)"$/\1/')
OLD="replayLostShare=0 oldModelShare=1.0 rehearsalProbability=0 Self-play.drillShare=0 Self-play.critDrillShare=0"
frozen() {
  local out=$1 net=$2 secs=$3 S E; shift 3
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py $FROZEN "$@" > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S); sleep "$secs"; E=$(date +%H:%M:%S)
  python report.py client.log "$S" "$E" > "$out"
}
cp /d/swordai/run/swordai/lost-cases.json /d/swordai/run/lost-cases-before-final-$label.json
for who in cand inst; do
  net=$cand; [ $who = inst ] && net=$INST
  frozen fp-$label-$who-sp.txt $net 600 $SPACER
  frozen fp-$label-$who-mix.txt $net 720 $MIX
  frozen fp-$label-$who-old.txt $net 480 $OLD
done
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
cp /d/swordai/run/lost-cases-before-final-$label.json /d/swordai/run/swordai/lost-cases.json
python - "$label" <<'PY' | tee -a final-pair.log
import re, sys
label = sys.argv[1]
ROW = re.compile(r'^(.{20}) +(\d+) +(\d+) +(\d+)% +(\d+)-(\d+) +(\d+)%')
HARD = ['timer (friend)', 'spacer (crit, S)', 'stander (3 blocks)', 'whiffer', 'jump-crit chaser', 'punisher', 'circler', 'no-knockback']
def rows(p):
    out = {}
    for line in open(p, encoding='utf-8', errors='replace'):
        m = ROW.match(line.rstrip('\n'))
        if m:
            out[m.group(1).strip()] = (int(m.group(2)), int(m.group(3)), int(m.group(5)), int(m.group(6)))
    return out
def net(r, k):
    x = r.get(k)
    return None if not x else (x[0] - x[1], 100.0 * x[2] / max(1, x[2] + x[3]), x[0], x[1])
res = {}
for who in ('cand', 'inst'):
    sp = rows('fp-%s-%s-sp.txt' % (label, who)); mix = rows('fp-%s-%s-mix.txt' % (label, who)); old = rows('fp-%s-%s-old.txt' % (label, who))
    hard = [mix[k][0] - mix[k][1] for k in HARD if k in mix]
    res[who] = (net(sp, 'spacer (crit, S)'), sum(hard) / max(1, len(hard)), net(old, 'the 0.14.0 model') or net(mix, 'the 0.14.0 model'))
def f(x):
    return 'n/a' if x is None else '%d/%d net %+d (%.0f%%)' % (x[2], x[3], x[0], x[1])
c, i = res['cand'], res['inst']
score = lambda r: (r[0][0] if r[0] else 0) + r[1] + 0.5 * (r[2][0] if r[2] else 0)
d = score(c) - score(i)
print('%s FINAL PAIRED | spacer-only cand %s vs inst %s | mix hard avg cand %+.0f vs inst %+.0f | 0.14.0 cand %s vs inst %s | score %+.0f: %s'
      % (label, f(c[0]), f(i[0]), c[1], i[1], f(c[2]), f(i[2]), d, 'BETTER' if d > 50 else 'WORSE' if d < -50 else 'about EQUAL'))
PY
