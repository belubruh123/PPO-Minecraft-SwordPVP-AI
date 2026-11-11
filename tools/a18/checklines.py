import re
CHECKLOG = r'D:/swordai/run/school-checks.log'
blocks=[]; cur=None
for line in open(CHECKLOG, errors='replace'):
    m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) check', line)
    if m:
        cur={'t':m.group(1)}; blocks.append(cur); continue
    if cur is None: continue
    m = re.match(r'ROUNDS WON: A (\d+)/(\d+).*B (\d+)/(\d+).*z ([+-]?[\d.]+)', line)
    if m: cur['ra']=int(m.group(1)); cur['n']=int(m.group(2)); cur['rb']=int(m.group(3)); cur['z']=m.group(5)
    for side,key in (('A','  A swings:'),('B','  B swings:')):
        if line.startswith(key):
            m=re.search(r'in their combo, sword ready, them in reach: (\d+) ticks, swung (\d+)%, crosshair off them (\d+)%', line)
            if m: cur[side+'ct']=int(m.group(1)); cur[side+'sw']=int(m.group(2)); cur[side+'off']=int(m.group(3))
            m=re.search(r'second hits in a row (\d+), a third after (\d+)', line)
            if m: cur[side+'2nd']=int(m.group(1)); cur[side+'3rd']=int(m.group(2))
            m=re.search(r'outside their combo on the ground, sword ready, them in reach: (\d+) ticks, swung (\d+)%', line)
            if m: cur[side+'oct']=int(m.group(1)); cur[side+'osw']=int(m.group(2))
    for side,key in (('A','  A (school) techniques:'),('B','  B (installed) techniques:')):
        if line.startswith(key):
            m=re.search(r' B1 (\d+)/(\d+) done', line)
            if m: cur[side+'b1']=int(m.group(1)); cur[side+'b1n']=int(m.group(2))
sel=[b for b in blocks if 'Act' in b and 'Bct' in b]
print('%-12s %5s %5s | %4s %3s %3s %4s %4s | %4s %3s %3s %4s %4s | %4s %4s | %3s %3s | %5s %5s'%('check','rA','rB','Act','sw','off','Asw#','Ahld','Bct','sw','off','Bsw#','Bhld','b1A','b1B','2A','2B','3A','3B'))
T={}
for b in sel:
    asw=b['Act']*b['Asw']/100; bsw=b['Bct']*b['Bsw']/100
    ah=b['Act']*(100-b['Asw']-b['Aoff'])/100; bh=b['Bct']*(100-b['Bsw']-b['Boff'])/100
    print('%-12s %2d/%2d %2d/%2d | %4d %3d %3d %4.0f %4.0f | %4d %3d %3d %4.0f %4.0f | %2d/%2d %2d/%2d | %3s %3s | %3s %3s'%(b['t'],b.get('ra',0),b.get('n',0),b.get('rb',0),b.get('n',0),b['Act'],b['Asw'],b['Aoff'],asw,ah,b['Bct'],b['Bsw'],b['Boff'],bsw,bh,b.get('Ab1',0),b.get('Ab1n',0),b.get('Bb1',0),b.get('Bb1n',0),b.get('A2nd'),b.get('B2nd'),b.get('A3rd'),b.get('B3rd')))
