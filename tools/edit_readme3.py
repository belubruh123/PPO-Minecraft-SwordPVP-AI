p='D:/swordai/README.md'
s=open(p,encoding='utf-8').read()
pairs=[
('''   - **The duel and self-play count only the fights on equal terms**, those in which the copy's
     knockback is not late (about one in five). The others are fought and learned from, but who wins
     them says what a slow connection is worth, not which of the two is the better fighter, and with
     them counted the duel could not be won.''',
'''   - **The duel and self-play count only the fights on equal terms**, those in which the copy's
     knockback is neither late nor shorter than the AI's own (about one in ten; in every other
     fight the copy is knocked back only six to ten tenths as far, which is the same advantage in
     small). The others are fought and learned from, but who wins them says what a slow connection
     is worth, not which of the two is the better fighter, and with them counted the duel could not
     be won. The duel is judged over the last 90,000 steps (30,000 before, which often held fewer
     than the twenty counted fights it takes).'''),
('''Over once it beats that copy in 80% of at least 20 fights on equal terms (0.14.0: every other fight is against the copy on a slow connection, and those are not counted).''',
'''Over once it beats that copy in 80% of at least 20 fights on equal terms (0.14.0: every other fight is against the copy on a slow connection, and in half of the rest the copy is knocked back less; those are not counted). Three fights in ten are still against the scripted opponents of the stages before.'''),
('''cannot be kept away, each crit drill, its fights by the opponent's connection (slow or ordinary) and the
combo drills.''',
'''cannot be kept away, each crit drill, its fights by the opponent's connection (slow or ordinary), the
combo drills, and from the duel on the fights it still has against the scripted opponents of earlier
stages (the one yardstick that does not move while its copy does).'''),
]
for a,b in pairs:
    assert s.count(a)==1,(a[:50],s.count(a))
    s=s.replace(a,b)
open(p,'w',encoding='utf-8',newline='').write(s)
print('ok')
