#!/usr/bin/env bash
# bench-run.sh TAG NET (env SUITE = the suite, default tools/bench/suite-v1.txt; MAX_MIN = minutes before it gives up, default 360):
# the benchmark (docs/BENCHMARK.md) of one network. Pauses the hourly loop, puts NET in the school's checkpoint as net-check.sh
# does, and plays the suite with it as side A through the check's bench mode (school/bench-suite.txt: "tag TAG", "network NAME",
# then the suite's lines; see SchoolSession.BENCH_SUITE_FILE), until run/swordai/bench/TAG/DONE. Then the training network goes
# back, the suite file is removed, training and the loop resume.
# Each episode is run/swordai/bench/TAG/NNNN-BOT.txt.gz (NNNN-h2h.txt.gz), in the user's real fights' format; hits.csv and
# jumps.csv there are the check's hit and jump logs, numbered by episode. Started again with the same TAG and NET after a break,
# it plays only the episodes not written yet. The h2h lines play a network of school/league/ (the one the line names, else the
# first there); with none there they are left out (DONE counts them).
S="${SWORDAI_TOOLS:-/d/swordai/tools}"
TAG="$1"; NET="$2"
SUITE="${SUITE:-$S/bench/suite-v1.txt}"
MAX_MIN=${MAX_MIN:-360}
[ -n "$TAG" ] && [ -f "$NET" ] && [ -f "$SUITE" ] || { echo "usage: bench-run.sh TAG NET (suite: $SUITE)"; exit 1; }
# The tag names a folder (SchoolSession checks it the same way).
case "$TAG" in [A-Za-z0-9_]*) ;; *) echo "bad tag $TAG"; exit 1;; esac
case "$TAG" in *[!A-Za-z0-9._-]*) echo "bad tag $TAG: letters, digits, . _ - only"; exit 1;; esac
C=school; C="${C}cycle"
L=/d/swordai/run/school-client-live.log
INBOX=/d/swordai/run/swordai/inbox.txt
SCH=/d/swordai/run/swordai/school
OUTD=/d/swordai/run/swordai/bench/$TAG
STOPF=/d/swordai/run/school-cycle.stop
H=$(sha256sum "$NET" | cut -d' ' -f1)
[ -f "$OUTD/DONE" ] && { echo "bench $TAG is done already ($OUTD/DONE): use another tag"; exit 1; }
if [ -f "$OUTD/network.txt" ] && [ "$(cut -d' ' -f1 "$OUTD/network.txt")" != "$H" ]; then
  echo "bench $TAG was begun with another network ($OUTD/network.txt): use another tag"; exit 1
fi
send() { printf '%s\r\n' "$1" > $INBOX; }
stops() { grep -ac "Stopped (" $L; }
stopped() { for i in $(seq 1 120); do [ $(stops) -gt $1 ] && return 0; sleep 1; done; return 1; }
looping() { ps -ef | grep -a "$C" | grep -qv grep; }
since() { tail -n +$start $L | grep -ac "$1"; }
[ -f $SCH/bench-suite.txt ] && { echo "$SCH/bench-suite.txt is there: another benchmark runs (or one was killed: then remove it)"; exit 1; }
# Never left behind: with the suite file in the school folder every later check (the hourly loop's too) would be a benchmark.
trap 'rm -f $SCH/bench-suite.txt $SCH/bench-hit-and-out $SCH/bench-air-yield' EXIT
# HIT_AND_OUT=1: side A plays with hit and out over the network (SchoolSession.BENCH_HIT_AND_OUT_FILE), for this run only.
[ -n "$HIT_AND_OUT" ] && touch $SCH/bench-hit-and-out && echo "hit and out on for this benchmark"
[ -z "$HIT_AND_OUT" ] && rm -f $SCH/bench-hit-and-out
# AIR_YIELD=1: run 88's air yield (SchoolSession.BENCH_AIR_YIELD_FILE) for side A, for a network trained with it (an "airyield" arm).
[ -n "$AIR_YIELD" ] && touch $SCH/bench-air-yield && echo "run 88's air yield on for this benchmark"
[ -z "$AIR_YIELD" ] && rm -f $SCH/bench-air-yield

# 1. The loop paused, and kept from starting: the stop file stays for the whole benchmark (with the loop not running the
# watchdog would otherwise start it in the middle, and its hourly check would stop the benchmark). A stop file someone else
# made stays as it is.
OWNSTOP=
[ -f $STOPF ] || { touch $STOPF; OWNSTOP=1; }
if looping; then
  for i in $(seq 1 900); do looping || break; sleep 2; done
  if looping; then [ -n "$OWNSTOP" ] && rm -f $STOPF; echo "the loop did not pause within 30 minutes: nothing done"; exit 1; fi
  echo "loop paused $(date +%H:%M:%S)"
fi
# The loop is started again by the watchdog (within 10 minutes, from run/ as it always starts it), not from here.
resume_loop() {
  [ -n "$OWNSTOP" ] || return 0
  rm -f $STOPF
  echo "stop file removed $(date +%H:%M:%S): the watchdog starts the loop again"
}

# 2. The check's other switches set aside (a benchmark plays as the user's game does), put back at the end.
ASIDE=
for f in check-from spar-check rules-off-check play-temperature; do
  [ -f $SCH/$f ] && mv $SCH/$f $SCH/$f.bench-aside && ASIDE="$ASIDE $f"
done
put_back() { for f in $ASIDE; do mv $SCH/$f.bench-aside $SCH/$f; done; }

# 3. Training stopped, NET in the school's checkpoint (as net-check.sh), the suite file written.
n=$(stops); send "swordai stop"
if ! stopped $n; then echo "stop did not take: nothing done"; put_back; resume_loop; exit 1; fi
sleep 2
KEEP="$SCH/training-kept-$(date +%H%M).bin"
if ! cp -p $SCH/checkpoint.bin "$KEEP"; then
  echo "could not keep the training network: nothing done"; put_back; send "swordai school"; resume_loop; exit 1
fi
cp -p "$NET" $SCH/checkpoint.bin
mkdir -p "$OUTD"
echo "$H $NET${HIT_AND_OUT:+ hit-and-out}${AIR_YIELD:+ air-yield}" > "$OUTD/network.txt"
cp "$SUITE" "$OUTD/suite.txt"
{ echo "# bench-run.sh $(date '+%Y-%m-%d %H:%M:%S')"; echo "tag $TAG"; echo "network $(basename "$NET")"; cat "$SUITE"; } > $SCH/bench-suite.txt
echo "bench $TAG: $(basename "$NET") on $(basename "$SUITE") from $(date +%H:%M:%S)"

# 4. The suite played: until DONE (the session then stops itself), a stop or failure, or MAX_MIN.
start=$(wc -l < $L)
send "swordai school check"
deadline=$(( $(date +%s) + MAX_MIN * 60 ))
RESULT=done
until [ -f "$OUTD/DONE" ]; do
  sleep 10
  [ -f "$OUTD/DONE" ] && break
  if [ $(since "could not start the school") -gt 0 ]; then RESULT="could not start (see the client log)"; break; fi
  if [ $(since "Stopped (") -gt 0 ]; then RESULT="stopped before the end: $(tail -n +$start $L | grep -a 'Stopped (' | tail -1 | sed -E 's/.*Stopped/Stopped/')"; break; fi
  if [ $(date +%s) -ge $deadline ]; then RESULT="not done after $MAX_MIN minutes"; break; fi
done
if [ $(since "could not start the school") -eq 0 ]; then
  for i in $(seq 1 60); do [ $(since "Stopped (") -gt 0 ] && break; sleep 1; done
  if [ $(since "Stopped (") -eq 0 ]; then n=$(stops); send "swordai stop"; stopped $n; fi
fi
sleep 2

# 5. Everything put back: the suite file, the training network, the switches; training and the loop resumed.
rm -f $SCH/bench-suite.txt $SCH/bench-hit-and-out $SCH/bench-air-yield
rm -f $SCH/checkpoint.bin
cp -p "$KEEP" $SCH/checkpoint.bin
if cmp -s "$KEEP" $SCH/checkpoint.bin; then echo "training network restored"; else echo "TRAINING NETWORK NOT RESTORED: it is in $KEEP"; fi
put_back
send "swordai school"
resume_loop
echo "bench $TAG: $RESULT; $(ls "$OUTD" | grep -cE '^[0-9]{4}-.+\.txt\.gz$') episode files in $OUTD"
[ -f "$OUTD/DONE" ] && cat "$OUTD/DONE"
