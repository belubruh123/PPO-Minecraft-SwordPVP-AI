import io

s = io.open('train27.sh', encoding='utf-8').read()
a = 'Self-play.reward.hitTaken=0.3'
assert s.count(a) == 1
s = s.replace(a, a + ' Crits.reward.jumpInReady=1.0 Duel.reward.jumpInReady=0.5 Self-play.reward.jumpInReady=0.5 "Combo escape.reward.jumpInReady=0.5"')
lines = s.split('\n')
for i, line in enumerate(lines):
    if line.startswith("printf 'swordai train") and 'inbox.txt' in line:
        lines[i] = ('if [ -n "$HEADLESS" ]; then printf \'swordai train headless\\r\\n\' > /d/swordai/run/swordai/inbox.txt; '
                    'else printf \'swordai train\\r\\n\' > /d/swordai/run/swordai/inbox.txt; fi')
        break
else:
    raise SystemExit('no train line')
s = '\n'.join(lines)
s = s.replace('# train27.sh [DIR]:', '# train28.sh [DIR]: train27 + a reward for the sword being ready when the opponent jumps in '
              '(jumpInReady; 20 Oct 11:00). HEADLESS=1 trains without the screen. train27.sh [DIR]:', 1)
io.open('train28.sh', 'w', encoding='utf-8', newline='').write(s)
print('ok')
