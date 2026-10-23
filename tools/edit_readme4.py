p='D:/swordai/README.md'
s=open(p,encoding='utf-8').read()
pairs=[
('''    - **The duel is the fight that does not come back that way.** The copy it fights there is saved
      when the duel begins and plays as a fake player. If the duel began without the screen, that
      copy is at home in its body and the AI on your screen is not: after two hours of the duel on
      the screen it landed 0.7 to 0.9 hits for each it took against it (about one for one where the
      copy's knockback was late, 0.5 to 0.7 where it was not) and won one fight in five on equal
      terms. It is still worth training there, since what it meets is a fighter that keeps a sword's
      length and punishes every mistake, but do not wait for the stage to be passed on the screen.
''',
'''    - **The duel is the fight that does not come back that way, and is not to be passed on the
      screen.** The copy it fights is a fake player on the server. Your character sees it as any
      player sees another, a tick or more late, and its clicks reach the server a tick late; the
      copy has neither delay. After two hours of the duel on the screen it landed 0.7 to 0.9 hits
      for each it took (about one for one where the copy's knockback was late, 0.5 to 0.7 where it
      was not) and won one fight in five on equal terms. The same network, put back without the
      screen, was level with the copy from the first update and passed the duel in 33 minutes
      (535,000 steps): 58, 62, 74, 78 and then 80 fights in 100 won on equal terms, with what it
      does against the scripted opponents unchanged (about 80 hits a minute landed and 34 taken).
      So: **the duel and self-play are trained without the screen** (`/swordai train headless`),
      and the screen is for the stretch before a fight. Against a person on a server both of you
      have that delay, which is what the slow-connection fights train.
'''),
]
for a,b in pairs:
    assert s.count(a)==1,(a[:50],s.count(a))
    s=s.replace(a,b)
open(p,'w',encoding='utf-8',newline='').write(s)
print('ok')
