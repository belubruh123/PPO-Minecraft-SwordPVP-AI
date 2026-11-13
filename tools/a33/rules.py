import sys,glob,collections
exec(open('D:/swordai/tools/a33/spacing.py').read().split("fs=sorted(glob")[0])
fs=sorted(set(f for f in glob.glob(D+"20261113-224*") if '-open' not in f))
C=collections.Counter();Cn=0
for f in fs:
    for r in load(f):
        d=float(r['dist']);ms=float(r['mySword']);os_=float(r['oppSword'])
        if d<3 and ms<0.9 and os_>=0.9:
            Cn+=1
            C['W' if 'W' in r['keys'] else 'S' if 'S' in r['keys'] else 'none']+=1
            for ch in r['rules']: C['rule_'+ch]+=1
            if 'W' in r['keys']:
                for ch in r['rules']: C['Wrule_'+ch]+=1
print(Cn,dict(C))
