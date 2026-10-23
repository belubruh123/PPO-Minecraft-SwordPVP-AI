"""filterlost.py: takes the no-knockback fights (kind 5) out of D:/swordai/run/swordai/lost-cases.json, so the 1-1
replays go to the fights a network has ever won (spacer, jumper, trader, punisher, timer). No network has won a
no-knockback fight (0 in 464 replays by 20 Oct 20:56); they are kept in lost-cases-noknock.json for their own run."""
import io, json
P = 'D:/swordai/run/swordai/lost-cases.json'
K = 'D:/swordai/run/lost-cases-noknock.json'
c = json.load(io.open(P, encoding='utf-8'))
out = [x for x in c['cases'] if x.get('kind') == 5]
c['cases'] = [x for x in c['cases'] if x.get('kind') != 5]
try:
    old = json.load(io.open(K, encoding='utf-8'))
except Exception:
    old = []
seen = {x.get('seed') for x in old}
old += [x for x in out if x.get('seed') not in seen]
json.dump(old, io.open(K, 'w', encoding='utf-8'), indent=1)
json.dump(c, io.open(P, 'w', encoding='utf-8', newline=''), indent=1)
print('lost fights: %d kept for replay, %d no-knockback set aside (%d in all set aside)' % (len(c['cases']), len(out), len(old)))
