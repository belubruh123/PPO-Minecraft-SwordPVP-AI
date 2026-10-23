#!/usr/bin/env bash
# evalfull.sh SECS LABEL=DIR [LABEL=DIR ...]: frozen test (20 Oct) of each network against every opponent kind with an even
# share of fights: the 0.14.0 model and the self-copy half the time, the other half the scripted kinds evenly (no drills,
# no replays, no mutants, no self-imitation, PFSP off). Writes final-LABEL.txt per network; then training again
# with the newest train script (train27.sh), from the network that was training before the test.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
secs=$1; shift
printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
sleep 8
cur=/d/swordai/run/kept-v23-before-eval-$(date +%H%M)
mkdir -p $cur && cp /d/swordai/run/swordai/checkpoint.bin /d/swordai/run/swordai/curriculum.properties /d/swordai/run/swordai/lost-cases.json $cur/
for pair in "$@"; do
  label=${pair%%=*}; net=${pair#*=}
  printf 'swordai stop\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 7
  python setcfg.py actorLr=0 criticLr=0 adaptiveLr=false minActorLr=0 silWeight=0 replayLostShare=0 mutateShare=0 \
    oldModelShare=0.5 rehearsalProbability=0.6 selfCritShare=1.0 hardOpponents=false counterCritRule=false playTemperature=1.0 \
    Crits.drillShare=0 Crits.critDrillShare=0 Crits.spacerShare=0.12 Crits.timerShare=0.12 Crits.whifferShare=0.08 \
    Crits.punisherShare=0.1 Crits.jumperShare=0.1 Crits.traderShare=0.04 Crits.runnerShare=0.04 Crits.circlerShare=0.08 \
    Crits.cornerShare=0.06 Crits.noKnockShare=0.08 Crits.oddShare=0.04 Crits.backerShare=0.04 > /dev/null
  cp "$net/checkpoint.bin" /d/swordai/run/swordai/checkpoint.bin
  cp "$net/curriculum.properties" /d/swordai/run/swordai/curriculum.properties
  printf 'swordai train\r\n' > /d/swordai/run/swordai/inbox.txt
  sleep 8
  S=$(date +%H:%M:%S)
  sleep "$secs"
  E=$(date +%H:%M:%S)
  echo "=== $label: $net, $S-$E"
  python report.py client.log "$S" "$E" | tee "final-$label.txt"
done
python setcfg.py hardOpponents=true Crits.drillShare=0.35 Crits.critDrillShare=0.3 > /dev/null
bash train27.sh $cur
echo "=== training again $(date +%H:%M:%S)"
