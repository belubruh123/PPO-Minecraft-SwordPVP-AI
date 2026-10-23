import sys
class Ed:
    def __init__(self, p):
        self.p = p
        self.s = open(p, encoding='utf-8', newline='').read()
        self.crlf = '\r\n' in self.s
    def rep(self, a, b, cnt=1):
        if self.crlf:
            a = a.replace('\r\n', '\n').replace('\n', '\r\n'); b = b.replace('\r\n', '\n').replace('\n', '\r\n')
        n = self.s.count(a)
        if n != cnt:
            raise SystemExit('%s: expected %d of %r, found %d' % (self.p, cnt, a[:80], n))
        self.s = self.s.replace(a, b)
    def save(self):
        open(self.p, 'w', encoding='utf-8', newline='').write(self.s)
        print('saved', self.p)
