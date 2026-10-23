p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()
a = s[s.index('    - **The duel is the fight that does not come back that way, and is not to be passed on the\n'):s.index('11. *Out of a combo by its second hit.*')]
b = '''    - **Against a copy of itself the screen costs it two ticks, and the copy is now given the
      same.** The copy is a fake player on the server. Your character sees it as any player sees
      another, a tick late, and its clicks reach the server a tick late; the copy had neither
      delay. After two hours of the duel on the screen it landed 0.7 to 0.9 hits for each it took
      and won one fight in five on equal terms; the same network, put back without the screen, was
      level with the copy from the first update and passed the duel in 33 minutes (535,000 steps):
      58, 62, 74, 78 and then 80 fights in 100 won on equal terms, with what it does against the
      scripted opponents unchanged (about 80 hits a minute landed and 34 taken). In stage 10, on
      the screen, against the copy that had just won the duel: 33 hits a minute landed and 55
      taken, out of a combo by the second hit one time in three.

      So on the screen the copy now sees the fighter two ticks later than it would
      (`screenCopyLagTicks` in `config.json`). With that the same fights came to 50 hits a minute
      landed and 44 taken, out by the second hit in about half the combos, and against the
      scripted opponents 83 landed and 27 taken: the fighter it is without the screen. Two ticks
      is what made the two level, not something measured on its own.
    - **The duel and self-play are still judged without the screen only** (`/swordai train
      headless`): see 13. below. On the screen the fights against the copy are fought and learned
      from, and the screen is for the stretch before a fight.

'''
s = s.replace(a, b)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
