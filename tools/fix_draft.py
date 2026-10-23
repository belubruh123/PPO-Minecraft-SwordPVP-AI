p = 'readme15_draft.md'
s = open(p, encoding='utf-8').read()
i = s.index('    - **It steps back after a hit that has not sent the opponent away**')
j = s.index('    - **It does to others what was done to it**')
s = s[:i] + '''    - **It steps back from an opponent that answers its hits** (`stepBackRule`; `StepBack`). Hit
      three to thirteen ticks after a hit of its own, its sword on the way back, twice within five
      seconds: that opponent is one that does. From then on, for as long as it goes on, the fighter
      is walking backwards from the tick each of its hits lands, until it is four blocks off or its
      sword is four fifths back, and then comes in again; if the hit turns out to have sent the
      opponent flying, or the opponent is running, it goes after it instead. No swing is made early
      for any of this. (Stepping back from every hit that had not sent the opponent away, read off
      the two to five ticks after it, was tried first. It protects as well, and against the opponent
      that keeps running out it cost three hits in ten: 43 landed a minute for 61.)
''' + s[j:]
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
