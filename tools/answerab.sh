#!/usr/bin/env bash
# answerab.sh SECS: frozen spacer-only test of the answer rule (20 Oct 11:25); as spacerab.sh: the current network with each rule switch in turn, to see
# which rules push it into the spacer's ready sword. Nothing learned, no replays/mutants/self-imitation, no 0.14.0 fights,
# no drills, PFSP off. Then training again with train26.sh (it puts every training setting back).
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
secs=${1:-480}
net=/d/swordai/run/kept-v23-answerab
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
mkdir -p $net && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $net/
run() {
  label=$1; shift
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 silWeight=0 replayLostShare=0 mutateShare=0 \
    oldModelShare=0 rehearsalProbability=0.95 selfCritShare=1.0 hardOpponents=false \
    Crits.drillShare=0 Crits.critDrillShare=0 Crits.spacerShare=1.0 Crits.timerShare=0 Crits.whifferShare=0 Crits.punisherShare=0 \
    Crits.jumperShare=0 Crits.traderShare=0 Crits.runnerShare=0 Crits.circlerShare=0 Crits.cornerShare=0 Crits.noKnockShare=0 \
    Crits.oddShare=0 Crits.backerShare=0 "$@" > /dev/null
  cp $net/checkpoint.bin /d/swordai/run/swordai/checkpoint.bin
  cp $net/curriculum.properties /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  echo "=== $label ($*): $S-$E"
  python report.py client.log "$S" "$E" | grep -a "spacer\|stander\|updates" | tee "spacer-$label.txt"
}
run S3 answerRule=true
run S4 answerRule=false
python setcfg.py answerRule=true hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
HEADLESS=1 bash train28.sh $net
echo "=== training again $(date +%H:%M:%S)"
