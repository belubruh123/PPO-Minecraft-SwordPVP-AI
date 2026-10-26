import re
lines=[l for l in open('D:/swordai/run/school-client.log',encoding='utf8',errors='replace') if 'school update' in l][-6:]
c=n=sf=sc=cf=cw=0; ck=sk=ma=pl='?'
for l in lines:
    m=re.search(r'jump-ins countered in the full fights (\d+) of (\d+)',l); 
    if m: c+=int(m[1]); n+=int(m[2])
    m=re.search(r'jump-in scenes kept (\d+), fought again (\d+), countered (\d+)',l)
    if m: sk=m[1]; sf+=int(m[2]); sc+=int(m[3])
    m=re.search(r'crit scenes kept (\d+) \(\d+ new\), fought again (\d+)(?:, won (\d+))?',l)
    if m: ck=m[1]; cf+=int(m[2]); cw+=int(m[3] or 0)
    m=re.search(r'mastered \d+ \((\d+) in all\)',l)
    if m: ma=m[1]
    m=re.search(r"installed network's pull ([0-9.]+)",l)
    if m: pl=m[1]
print(f"last 6 updates: JUMP-INS COUNTERED in full fights {c} of {n} ({100*c/max(1,n):.0f}%); jump-in scenes kept {sk}, countered {sc} of {sf}; crit scenes kept {ck}, won {cw} of {cf}, mastered {ma} in all; pull {pl}")
