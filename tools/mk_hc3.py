"""mk_hc3.py: hourlycheck3.sh = hourlycheck2.sh that waits for the user's recordings; startT4b.sh uses it."""
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))
s = open('hourlycheck2.sh', encoding='utf-8').read()


def rep(old, new):
    global s
    assert s.count(old) == 1, old
    s = s.replace(old, new)


rep("# hourlycheck2.sh TRAIN_SCRIPT", "# hourlycheck3.sh (22 Oct 23:00) = hourlycheck2.sh that waits for the user's recordings (/swordai demo): while\n"
    "# run/swordai/demo.lock is there no inbox command is sent and the clock of a training stretch or a frozen run stands still.\n"
    "# hourlycheck2.sh TRAIN_SCRIPT")
rep("LOST=$(grep", "LOCK=/d/swordai/run/swordai/demo.lock\n"
    "nodemo() { while [ -f $LOCK ]; do sleep 10; done; }\n"
    "waitfor() { local left=$1; while [ $left -gt 0 ]; do sleep 10; [ -f $LOCK ] || left=$(( left - 10 )); done; }\n"
    "LOST=$(grep")
rep("  local out=$1 net=$2 secs=$3 S E; shift 3\n", "  local out=$1 net=$2 secs=$3 S E; shift 3\n  nodemo\n")
rep('  sleep "$secs"\n', '  waitfor "$secs"\n')
rep("  sleep $(( mins * 60 ))\n", "  waitfor $(( mins * 60 ))\n  nodemo\n")
rep("  if [ $(( n % 4 )) -ge 2 ]; then control; fi\n", "  if [ $(( n % 4 )) -ge 2 ]; then control; fi\n  nodemo\n")
rep("  bash $train $from > /dev/null", "  nodemo\n  bash $train $from > /dev/null")
open('hourlycheck3.sh', 'w', encoding='utf-8', newline='').write(s)

t = open('startT4.sh', encoding='utf-8').read()
assert t.count('hourlycheck2.sh') == 1
t = t.replace('hourlycheck2.sh', 'hourlycheck3.sh')
t = t.replace('# startT4.sh FROM', "# startT4b.sh FROM: startT4.sh with the loop that waits for the user's recordings (hourlycheck3.sh).\n# startT4.sh FROM", 1)
open('startT4b.sh', 'w', encoding='utf-8', newline='').write(t)
print('ok')
