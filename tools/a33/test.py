import glob,collections
exec(open('D:/swordai/tools/a33/spacing.py').read().split("fs=sorted(glob")[0])
fs=sorted(set(f for f in glob.glob(D+"20261113-224*") if '-open' not in f))
old=sorted(f for f in glob.glob(D+"2026110*")+glob.glob(D+"2026111[0-2]*") if '-won' in f)
def go(name,files):
    C=collections.defaultdict(lambda:[0,0,0])
    for f in files:
        rows=load(f)
        for i,r in enumerate(rows[:-6]):
            d=float(r['dist']);ms=float(r['mySword']);os_=float(r['oppSword'])
            if d<3 and ms<0.9 and os_>=0.9 and 'TAKEN' not in r['event']:
                k='S' if 'S' in r['keys'] else 'W' if 'W' in r['keys'] else 'none'
                b='<1.5' if d<1.5 else '1.5-3'
                nxt=rows[i+1:i+7]
                C[(b,k)][0]+=1
                C[(b,k)][1]+=any('TAKEN' in x['event'] for x in nxt)
                C[(b,k)][2]+=any('DEALT' in x['event'] for x in nxt)
    print(name)
    for k in sorted(C): n,t,dl=C[k]; print(" ",k,"n",n,"taken within 6 ticks %.0f%%"%(100*t/n),"dealt %.0f%%"%(100*dl/n))
go("tonight",fs);go("old",old)
