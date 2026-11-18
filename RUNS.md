# Runs

One line per run: the date, the one structural change, the literal problem it fixes, and the result (rounds won in the
200-round head-to-head against the installed network, and the user's real duels when tested). Runs 1-125 are in the
git tag `backup-before-cleanup-2026-11-18` (EXPERIMENTS.md).

| # | Start | From network | The one change | Result | Kept? | Why (the literal problem) |
|---|---|---|---|---|---|---|
| 35 | 3 Nov | run 30h line | combo scenes in training | the user: "near perfect on humans" (15 Nov) | anchor | - |
| 125 | 17 Nov | checked-1117-1913 | combo-hold drill after its own stun | not judged | - | last run of the old method |
| 126 | 18 Nov | - | cleanup: 430 dead scripts and ~800 KB of docs deleted, one short SOP (CLAUDE.md); the old pre-school pipeline (Trainer, train/headless/spar/demo/learn sessions, ~9k lines) removed | no training change; `/swordai school` and `/swordai fight` unchanged | yes | the project was too complex to reason about |
