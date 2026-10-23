p='D:/swordai/README.md'
s=open(p,encoding='utf-8').read()
pairs=[
('''   - **The duel and self-play count only the fights on equal terms**, those in which the copy's
     knockback is neither late nor shorter than the AI's own (about one in ten; in every other
     fight the copy is knocked back only six to ten tenths as far, which is the same advantage in
     small). The others are fought and learned from, but who wins them says what a slow connection
     is worth, not which of the two is the better fighter, and with them counted the duel could not
     be won. The duel is judged over the last 90,000 steps (30,000 before, which often held fewer
     than the twenty counted fights it takes).''',
'''   - **The duel and self-play count only the fights on equal terms**, and one fight in five
     against the copy is set up that way: nothing late for either, the same knockback, no stutter
     in what the AI sees, and the two start facing each other. In all the others something is
     against the AI and nothing against the copy (its knockback late, or six to ten tenths as far;
     the AI's picture of it coming in fits and starts; the copy starting with its eyes on the AI).
     Those are fought and learned from, but who wins them says what a slow connection is worth, not
     which of the two is the better fighter. Counted with them, the duel could not be won, and in
     self-play a fighter put against a copy of itself made that minute lost 18 of 24: at one win
     in ten self-play throws the fighter away and starts again from the copy. The duel is judged
     over the last 60,000 steps (30,000 before, which often held fewer than the twenty counted
     fights it takes).'''),
('''Over once it beats that copy in 80% of at least 20 fights on equal terms (0.14.0: every other fight is against the copy on a slow connection, and in half of the rest the copy is knocked back less; those are not counted). Three fights in ten are still against the scripted opponents of the stages before.''',
'''Over once it beats that copy in 80% of at least 20 fights on equal terms (0.14.0: one fight in five is set up on equal terms and counted; the rest have the copy on a slow connection, knocked back less, or the AI's view of it stuttering, and are not). Three fights in ten are still against the scripted opponents of the stages before.'''),
]
for a,b in pairs:
    assert s.count(a)==1,(a[:50],s.count(a))
    s=s.replace(a,b)
open(p,'w',encoding='utf-8',newline='').write(s)
print('ok')
