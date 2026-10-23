import struct, sys
f = open(sys.argv[1], 'rb')
rd = lambda fmt: struct.unpack('>' + fmt, f.read(struct.calcsize('>' + fmt)))
magic, fmt, vec, priv, logits = rd('iiiii'); steps, ups = rd('qq')
print('vec', vec, 'priv', priv, 'logits', logits)
for net in ('actor', 'critic'):
    s, = rd('q'); n, = rd('i'); tot = 0
    for _ in range(n):
        l, = rd('H'); name = f.read(l).decode(); ln, = rd('i'); f.seek(ln * 12, 1); tot += ln
        print('  %-18s %7d' % (name, ln))
    print(net, 'total', tot)
