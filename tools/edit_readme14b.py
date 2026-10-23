p = 'D:/swordai/README.md'
s = open(p, encoding='utf-8').read()

NUMBERS = '''**What it came to**, measured on the screen in first person (`/swordai speed 4`, stage 8, an
    hour and a half of fights after the rules went in; hits are per minute of fighting, landed and
    taken):

    | | before | with the rules |
    |---|---|---|
    | falls after a hit with the opponent in reach that end in a critical hit | 19% | 64 to 78% |
    | ... against the one that goes round it | | 65 to 79% |
    | ... against the one in the corner | | 63 to 81% |
    | ... against the one that takes no knockback | | 63 to 81% |
    | the opponent never under the crosshair on such a fall | 49% | 8% |
    | the sword spent on a plain hit first | 21% | 5 to 14% |
    | against the runner: hits landed and taken | 36 and 11 | 42 to 63 and 9 to 16 |
    | jumps made after an opponent that is getting away, per ten minutes | 1 | 75 to 115 |
    | ... that end in a critical hit | 0 | 16 to 37 |
    | far away (11 blocks or more, crosshair on the opponent): ticks the camera is still | 15 to 23% | 57 to 81% |
    | ... times it changes the way it is turning, per 100 ticks | 13 to 16 | 0 to 4 |
    | swings made before the sword was back | 0 | 0 |

    Of the jumps after an opponent that is getting away, about half never bring it into reach:
    those are the jumps on the way, after one that runs. What is left of the falls that do not end
    in a critical hit: the sword was not back yet (one in ten; it had just swung), the opponent
    was behind it and the turn took longer than the fall (one in twelve), or it was the first hit
    of that opponent, which is answered at once.

    Against each kind, hits landed and taken a minute and the share of its hits that were critical:
    the one a sprint hit knocks away 68 to 78 and 17 to 45 (10 to 36%; every other one of these is
    now drawn afresh, some of them rushing in for critical hits); the one that cannot be kept off
    58 to 69 and 82 to 86 (55 to 73%); the runner 49 to 58 and 10 to 16; the one that goes round it
    71 to 77 and 55 to 64 (35 to 51%); the one in the corner 78 to 81 and 42 to 47; the one that
    takes no knockback 71 to 86 and 89 to 96 (72 to 75%); the odd ones 56 to 71 and 28 to 48. The
    one that takes no knockback and the one that cannot be kept off still land more hits than
    they take: nothing the fighter does moves them, and what it has against them is that three of
    four of its own hits are critical.

    **Not tried against a person.** All of it is from training fights against scripted opponents.
    The uppercut is rare (a handful in ten minutes): it only comes up when the opponent is a step
    out of reach as the sword comes back.
'''
assert s.count('NUMBERS_14') == 1
s = s.replace('NUMBERS_14', NUMBERS.rstrip('\n'))
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
