import re,math,sys
names=sys.argv[2:]
rows={};crit={'A':[0]*5,'B':[0]*5};blk=None;n=0
for l in open(sys.argv[1],encoding='utf-8',errors='replace'):
    if l.startswith('=== '):
        blk=None
        m=re.search(r'SCREEN 1: (\S+) at temperatures 1, scripted',l)
        if m and m.group(1) in names: blk=m.group(1);n+=1
        continue
    if not blk: continue
    p=l.split()
    if len(p)>=9 and re.match(r'^-?\d+$',p[1]) and p[0]!='ALL':
        try: a,b=float(p[2]),float(p[3]);se=float(p[5]);k=int(p[1])
        except: continue
        r=rows.setdefault(p[0].rstrip('*'),[0,0,0,0,0,0,0,0])
        r[0]+=k;r[1]+=a*k;r[2]+=b*k;r[3]+=(se*k)**2
        d=re.findall(r'(\d+)/(\d+)',l)
        if len(d)>=2:
            r[4]+=int(d[-2][0])*k;r[5]+=int(d[-2][1])*k;r[6]+=int(d[-1][0])*k;r[7]+=int(d[-1][1])*k
    m=re.match(r'\s+([AB]) crit moments: (\d+) ticks, falling (\d+), and in reach (\d+), and charged (\d+), and not sprinting \(server\) (\d+)',l)
    if m:
        c=crit[m.group(1)]
        for i in range(5): c[i]+=int(m.group(i+2))
print('screens',n)
print('%-18s %5s %7s %7s %6s %5s  %s'%('bot','pairs','A','B','A-B','se','A d/t  B d/t'))
for k,r in sorted(rows.items(),key=lambda x:(x[1][1]-x[1][2])/x[1][0]):
    t=r[0];print('%-18s %5d %7.1f %7.1f %6.1f %5.1f  %d/%d  %d/%d'%(k,t,r[1]/t,r[2]/t,(r[1]-r[2])/t,math.sqrt(r[3])/t,r[4]/t,r[5]/t,r[6]/t,r[7]/t))
print('crit funnel ticks/falling/in reach/charged/not sprinting  A',crit['A'],' B',crit['B'])
