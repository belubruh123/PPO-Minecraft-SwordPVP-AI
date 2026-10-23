p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()
pairs = [
    ('''The run these numbers are from is the one in *Running it with nobody at the keyboard*: each update writes
one line to the log with what the fights of each kind came to.
''',
     '''13. *Against a copy of itself, whoever moved first on a tick won.* Stage 10 was reached (5.37
    million steps), and there the fighter lost to a copy of itself made that minute: 10 fights won
    and 25 lost on equal terms. The cause was the order of a tick. The copy made its move, then the
    fighter. Two copies of one fighter come into reach on the same tick and, with the rule about
    answering (12.), trade hit for hit on the same tick from then on; they land as many hits as
    they take, and when both are a hit from the end the hit dealt first is the one that counts.
    Tried both ways for ten minutes: with the fighter first on every tick it won 16 fights of 23,
    with the copy first 2 of 14, landing 292 hits to 278 and 218 to 219.

    Now who moves first against a copy is drawn on every tick (`selfPlayTurns` in `config.json`),
    as on a server it is whichever player's click arrives first. Against the same copy the fighter
    then won 22 and lost 26, which is what a fighter and a copy of it half an hour old should come
    to. The duel was passed under the old order, with the copy first: four fights in five won
    against a copy that had the first move on every tick.

    Two things follow for training. **The duel and self-play are judged without the screen only.**
    On the screen your character is a tick or two behind a copy that plays on the server itself,
    and counted, those fights would never let it take the copy's place; at one win in ten self-play
    would throw away what the screen had taught it and start again from the copy. On the screen
    they are fought and learned from, and `/swordai status` says so. And `/swordai status` shows
    how its fights on equal terms have gone since training was started.

The run these numbers are from is the one in *Running it with nobody at the keyboard*: each update writes
one line to the log with what the fights of each kind came to.
'''),
    ('''10. **Self-play** - the same fight, but now the copy keeps up: whenever it clearly beats the copy, it becomes the copy the next one has to beat. This stage has no end.''',
     '''10. **Self-play** - the same fight, but now the copy keeps up: whenever it clearly beats the copy (65% of 20 or more fights on equal terms, and half of those against the copies before it), it becomes the copy the next one has to beat. This stage has no end. Like the duel it moves on in `/swordai train headless` only; on the screen the fights are fought and not counted.'''),
]
for a, b in pairs:
    assert s.count(a) == 1, (a[:60], s.count(a))
    s = s.replace(a, b)
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
