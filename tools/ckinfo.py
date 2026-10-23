import struct, sys
def info(path):
    f = open(path, 'rb')
    rd = lambda fmt: struct.unpack('>' + fmt, f.read(struct.calcsize('>' + fmt)))
    magic, fmt, vec, priv, logits = rd('iiiii')
    steps, ups = rd('qq')
    out = []
    for net in ('actor', 'critic'):
        s, = rd('q'); n, = rd('i')
        names = []
        for _ in range(n):
            l, = rd('H'); name = f.read(l).decode(); ln, = rd('i'); f.seek(ln * 12, 1); names.append((name, ln))
        out.append((net, n, names))
    return vec, priv, logits, steps, ups, out
for p in sys.argv[1:]:
    vec, priv, logits, steps, ups, out = info(p)
    print(p, 'vec', vec, 'priv', priv, 'steps', steps, 'updates', ups, ' '.join('%s %d tensors' % (o[0], o[1]) for o in out))
    print('   last actor tensors:', out[0][2][-3:])
