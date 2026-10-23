"""Puts the dev run's config.json into the state the night's training uses: python traincfg.py [measure|train]"""
import subprocess
import sys

mode = sys.argv[1] if len(sys.argv) > 1 else 'train'
common = ['Crits.reward.comboBack=0', 'Duel.reward.comboBack=0', 'Self-play.reward.comboBack=0']
if mode == 'measure':
    args = ['actorLr=0', 'criticLr=0', 'adaptiveLr=false', 'minActorLr=0', 'rehearsalProbability=0.9',
            'Crits.jumperShare=0.42', 'Crits.punisherShare=0.3', 'Crits.traderShare=0.04', 'Crits.runnerShare=0.04',
            'Crits.circlerShare=0.04', 'Crits.cornerShare=0.03', 'Crits.noKnockShare=0.04', 'Crits.oddShare=0.03']
else:
    args = ['actorLr=0.0003', 'criticLr=0.001', 'adaptiveLr=true', 'minActorLr=0.00003', 'rehearsalProbability=0.5',
            'Crits.jumperShare=0.28', 'Crits.punisherShare=0.18', 'Crits.traderShare=0.07', 'Crits.runnerShare=0.1',
            'Crits.circlerShare=0.1', 'Crits.cornerShare=0.07', 'Crits.noKnockShare=0.08', 'Crits.oddShare=0.07'] + common
subprocess.check_call([sys.executable, __file__.replace('traincfg.py', 'setcfg.py')] + args)
