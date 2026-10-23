import io
import os

os.chdir('C:/Users/jerem/.claude/projects/D--swordai/memory')


def rw(p, old, new):
    s = io.open(p, encoding='utf-8').read()
    if old is None:
        s = s.rstrip('\n') + '\n\n' + new
    else:
        assert s.count(old) == 1, (p, old)
        s = s.replace(old, new)
    io.open(p, 'w', encoding='utf-8', newline='').write(s)


rw('swordai-safe-learn.md',
   'Next: smoke test in the dev world (Carpet fake player as opponent), then version 0.15.3 + install with backup.',
   '**Tested 19 Oct 22:47-22:57 and installed as 0.15.3 (22:58, network unchanged = kept-v21-final-1950, backup '
   'D:\\swordai\\profile-backups\\before-0.15.3-20261019-225819).** The first test found turns never alternated: a Carpet '
   'fake player leaves the instant it dies, so no knockout was ever seen (some servers do the same). Fix: a turn also '
   'ends when the opponent is gone after >=100 ticks of fighting, or after 1200 ticks with no knockout '
   '(`FightLearnSession.endTurn`, SHORTEST/LONGEST_TURN_TICKS). Second test: turns alternated, a score line each fight '
   '(proven +173/min over 11, copy +181 over 12, no promotion), learning.bin/.properties written, checkpoint.bin md5 '
   'unchanged, no exceptions. Test script: scratchpad `safetest.sh MIN` (dev-only inbox pass-through of any command, '
   'gated by `FabricLoader.isDevelopmentEnvironment()`).')

rw('swordai-lost-replay.md', None,
   '**Fixes 19 Oct 22:40 (0.15.3):** (1) replays had been tallied under their opponent kind (the replay check sat after '
   'the kind checks in `episodeTally`), so per-kind damage rows 21:49-22:57 include replays of the worst fights (they look '
   'worse than a fair draw) and the PFSP kind weights were inflated; now replays go to "replays of lost fights" only. '
   '(2) Knockout-decided fights count even if under 200 ticks. (3) 30% of picks are the stalest kept fight (PLR '
   'staleness): three no-knockback/jumper fights lost by 250-300/min had taken most replays (27 replays, 0 wins) while '
   'timer/spacer fights waited. (4) Beaten fights go to an archive (`beatenCases`, max 400) and are re-tested (15% of '
   'replays, and all replays once a stage has none left); lost again means back on the list. The log says "ALL BEATEN" '
   'when the list is empty and every beaten one was re-tested and won: then turn on the outside bot (needs the Carpet '
   'PvP jar; verify every kind still fights after the switch).\n')

rw('swordai-user-reminders.md', '**State at 21:55 19 Oct:**',
   '**State at 23:00 19 Oct:** 0.15.3 installed (safe learning tested; network still kept-v21-final-1950). Training '
   'resumed 22:57 from kept-v22-pretest with the lost-fight fixes; per-kind rows before 22:57 included replays.\n\n'
   '**State at 21:55 19 Oct:**')
print('ok')
