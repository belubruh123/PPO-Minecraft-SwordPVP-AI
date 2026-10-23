#!/usr/bin/env bash
# train29.sh [DIR]: train28 + more exploration of when to swing and jump (20 Oct 11:45): the attack head's entropy had
# collapsed to ~0.015 nats (entropyCoef 0.01 x stage scale 0.3), so it never tried holding a swing for the opponent's
# jump in. entropyCoef[JUMP]=0.02, [ATTACK]=0.03. HEADLESS=1 trains without the screen.
cd "/c/Users/jerem/AppData/Local/Temp/claude/D--swordai/a22e256c-5a71-40c7-b784-e1011c2e02b3/scratchpad"
python - <<'EOF'
import json
P = 'D:/swordai/run/swordai/config.json'
c = json.load(open(P, encoding='utf-8'))
c['entropyCoef'] = [0.01, 0.01, 0.01, 0.01, 0.01, 0.02, 0.03]
json.dump(c, open(P, 'w', encoding='utf-8', newline=''), indent=2)
EOF
bash train28.sh "$@"
grep -a '"entropyCoef"' -A8 /d/swordai/run/swordai/config.json | tr -d ' \n'; echo
