p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()
i = s.index('    **What it came to**, measured on the screen in first person')
j = s.index('    **Something it learned from the rules that it should not have.**')
new = '''    **What it came to**, measured on the screen in first person (`/swordai speed 4`): "before" is
    the network as it was installed on 12 October, in stage 8; "now" is the last day of training
    with the rules, stage 8 and then self-play with the stage 8 opponents mixed in, twenty hours in
    all. Hits are per minute of fighting, landed and taken; ranges are from one hour to the next.

    | | before | now |
    |---|---|---|
    | swings made before the sword was back | 0 | 0 |
    | falls after a hit, opponent in reach, that end in a critical hit: against the one that goes round it | 19% (all) | 60 to 69% |
    | ... against the one in the corner | | 67 to 80% |
    | ... against the one that takes no knockback | | 86 to 94% |
    | ... against the odd ones and the other scripted opponents | | 61 to 75% |
    | the opponent never under the crosshair on such a fall | 49% | 8 to 12% |
    | against the runner: hits landed and taken | 36 and 11 | 46 to 56 and 13 to 17 |
    | jumps in at an opponent beyond its reach, per hour of fighting | about 5 | 90 to 115 |
    | ... that end in a critical hit | none | one in three (seven to nine in ten where the opponent is not running) |
    | ... hit out of the air, against a copy of itself, per minute | | 0.3 (1.8 before it remembered) |
    | uppercuts landed, per hour of fighting | none | 27 to 36; six in ten of the jumps made for one |
    | far away (11 blocks or more, crosshair on the opponent): ticks the camera is still | 15 to 23% | 39 to 56% |
    | ... times it changes the way it is turning, per 100 ticks | 13 to 16 | 1 to 2 |
    | sweeps, of its hits | 5.6% | 3.8 to 5.4% |
    | combos on it that end at the second hit | 59% | 62 to 76% |
    | combos that go three hits or more without one swing back | 4.1% | 4.5 to 7.1% |

    The last line is the one it is held to hardest, and it is not at nothing. Against the scripted
    opponents it is: none to one combo in a hundred. Against a copy of itself it is one in
    fifteen, and one in seven where the fight begins with the copy already two hits into a combo.
    Those are combos in which the copy was never under its crosshair within a sword's reach: on
    the screen it sees its opponent a tick or two late, an opponent that comes in at a run is in
    its own reach before it is in the fighter's, and the hit throws the fighter clear. What it
    does about those is back out (11.), which is the other three combos in four.

    Of the jumps in, about half never bring the opponent into reach: those are the jumps on the
    way, after one that runs. What is left of the falls that do not end in a critical hit: the
    sword was not back yet (one in ten; it had just swung), the opponent was behind it and the
    turn took longer than the fall (one in ten), or the swing was made at once as a trade.
    Against a copy of itself that last one is the rule and not the exception: seven falls in ten
    there are answered at once.

    Against each kind, hits landed and taken a minute and the share of its hits that were critical:
    a copy of itself 47 to 51 and 47 to 52 (8%); the runner 46 to 56 and 13 to 17 (20%); the one
    that goes round it 65 to 69 and 59 to 68 (41 to 51%); the one in the corner 68 to 77 and 46
    to 62 (40 to 56%); the one that takes no knockback 89 to 94 and 97 to 99 (92 to 96%); the odd
    ones 69 to 74 and 27 to 44 (20 to 37%); the other scripted opponents 72 to 76 and 37 to 45
    (24 to 32%). **The one that takes no knockback it does not beat:** nothing moves that one, its
    critical hits cannot be stepped out of, and what is left is hitting back as hard, which it
    does, fall for fall. The one that goes round it is about even in health for the same reason.

'''
s = s[:i] + new + s[j:]
a = '''    **Not tried against a person.** All of it is from training fights against scripted opponents.
    The uppercut is rare (a handful in ten minutes): it only comes up when the opponent is a step
    out of reach as the sword comes back.
'''
assert s.count(a) == 1
s = s.replace(a, '''    **Not tried against a person.** All of it is from training fights, on the screen, against
    scripted opponents and copies of itself.
''')
a = '''comes to nothing and not being hit is what pays; the network that is installed is the one from
    before that habit, trained on for a while with the cost in place.'''
assert s.count(a) == 1
s = s.replace(a, '''comes to nothing and not being hit is what pays; the network that is installed is the one from
    before that habit, trained on for three quarters of an hour with the cost in place (against
    the one in the corner: 68 hits landed and 60 taken a minute over that time, so that habit is
    not all gone).''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
