"""SwordAI school progress panel: a small local web server that reads the school's logs and serves a live page.

    python tools/dashboard/server.py [--run D:/swordai/run] [--port 8765]

then open http://localhost:8765. It only reads files: run/school-client*.log (one line per school update),
run/school-checks.log (the paired checks against the installed model) and the technique and stage lists in the source.
"""
import argparse
import glob
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
WINDOW = 40          # a technique's rate is over its last 40 moments, as the school judges it
MIN_MOMENTS = 8      # fewer than this and no rate is shown

# ---- what the techniques and stages are, read from the source ---------------------------------------------------------


def read_techniques():
    src = open(os.path.join(REPO, 'core/src/main/java/dev/swordai/core/pvp/Technique.java'), encoding='utf8').read()
    out = []
    for name, tid, title in re.findall(r'^\s*([A-Z0-9_]+)\("([^"]+)",\s*"([^"]+)"', src, re.M):
        out.append({'enum': name, 'id': tid, 'title': title})
    return out


def read_stages(techniques):
    src = open(os.path.join(REPO, 'core/src/main/java/dev/swordai/core/school/SchoolStages.java'), encoding='utf8').read()
    by_enum = {t['enum']: t['id'] for t in techniques}
    stages = []
    for m in re.finditer(r'new Stage\("(T-[A-Z])",\s*"([^"]+)",(.*?)(?:MIN|Long)', src, re.S):
        ids = [by_enum[e] for e in re.findall(r'Technique\.([A-Z0-9_]+)', m.group(3)) if e in by_enum]
        stages.append({'name': m.group(1), 'family': m.group(2), 'techniques': ids})
    prio = [by_enum[e] for e in re.findall(r'PRIORITY\[Technique\.([A-Z0-9_]+)\.ordinal\(\)\]\s*=\s*true', src) if e in by_enum]
    return stages, prio


# ---- the school's update lines -----------------------------------------------------------------------------------------

UPDATE = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*?\(swordai\) school update (\d+), (\d+) steps')
CHAT = re.compile(r'^\[(\d\d:\d\d:\d\d)\].*\[SwordAI\] (T-[A-Z]) .*?damage a minute (\d+) dealt, (\d+) taken')
DONE = re.compile(r'(\S+) (\d+)/(\d+) done \(\d+%\), (\d+) paid')
TAUGHT = re.compile(r'(\S+) (\d+) \((\d+)% as taught\)')
GAME = re.compile(r'([A-Za-z][A-Za-z ]*?) \(([A-Z0-9a-z/]+)\) (\d+)/(\d+)')


# About an hour of training (some 3600 steps a minute), and the least gain in it that is not standing still.
HOUR = 216000
STUCK_GAIN = 5.0


def section(parts, head):
    for p in parts:
        p = p.strip()
        if p.startswith(head):
            return p[len(head):].strip()
    return ''


def parse_update(line, m):
    parts = line.split(' | ')
    u = {'time': m.group(1), 'update': int(m.group(2)), 'steps': int(m.group(3))}
    stage = parts[1].strip() if len(parts) > 1 else ''
    sm = re.match(r'(T-[A-Z]) (.*?), (\d+) of at least (\d+) steps, installed network\'s pull ([\d.]+)', stage)
    if sm:
        u.update(stage=sm.group(1), family=sm.group(2), stageSteps=int(sm.group(3)), minSteps=int(sm.group(4)),
                 installedPull=float(sm.group(5)))
    kl = re.search(r'kl ([\d.]+)', parts[0])
    u['kl'] = float(kl.group(1)) if kl else None
    u['done'] = {}
    for tid, d, n, paid in DONE.findall(section(parts, 'done since the last update:')):
        u['done'][tid] = [int(d), int(n), int(paid)]
    u['taught'] = {}
    for tid, ticks, pct in TAUGHT.findall(section(parts, 'its moments by technique (network\'s likeliest keys):')):
        u['taught'][tid] = [int(ticks), int(pct)]
    keys = re.search(r'jump (\d+)/(\d+) swing (\d+)/(\d+) S (\d+)/(\d+)', section(parts, 'jumps, swings, S a minute off/on moments:'))
    u['keys'] = [int(x) for x in keys.groups()] if keys else None
    u['game'] = [[a.strip(), tid, int(x), int(y)] for a, tid, x, y in GAME.findall(section(parts, 'by the game:'))]
    sh = re.search(r'sprint hits (\d+)/(\d+)', section(parts, 'by the game:'))
    if sh:
        u['game'].append(['sprint hits', '', int(sh.group(1)), int(sh.group(2))])
    ji = re.findall(r'(\d+)', section(parts, 'jumping in:'))
    u['jumpIn'] = [int(x) for x in ji[:5]] if len(ji) >= 5 else None
    # The decider (since 24 Oct 09:53): how often it chose as the teacher, on its techniques, on its starts of one.
    dm = re.search(r'chose as the teacher (\d+)%, on its techniques (\d+)% of (\d+), its starts (\d+)% of (\d+), '
                   r'a start where it did none ([\d.]+)% of (\d+)', section(parts, 'decider'))
    u['decider'] = [float(x) for x in dm.groups()] if dm else None
    cm = re.findall(r'(\d+)', section(parts, 'crit moments:'))
    u['crit'] = [int(x) for x in cm[:9]] if len(cm) >= 9 else None
    return u


class LogCache:
    """Parsed updates per log file, parsed again only when the file changes."""

    def __init__(self, run):
        self.run = run
        self.files = {}
        self.lock = threading.Lock()

    def parse_file(self, path):
        ups = []
        pending = None
        with open(path, encoding='utf8', errors='replace') as f:
            for line in f:
                if 'school update' in line:
                    m = UPDATE.match(line)
                    if m:
                        try:
                            pending = parse_update(line.rstrip('\n'), m)
                            ups.append(pending)
                        except Exception:
                            pending = None
                elif '[SwordAI] T-' in line and pending is not None:
                    m = CHAT.match(line)
                    if m:
                        pending['dealt'] = int(m.group(3))
                        pending['taken'] = int(m.group(4))
                        w = re.search(r'weakest: ([^|]*)', line)
                        pending['weakest'] = w.group(1).strip() if w else ''
                        pending = None
        return ups

    def updates(self):
        paths = glob.glob(os.path.join(self.run, 'school-client*.log'))
        paths.sort(key=lambda p: os.path.getmtime(p))
        out = []
        with self.lock:
            for p in paths:
                try:
                    st = os.stat(p)
                except OSError:
                    continue
                key = (st.st_mtime, st.st_size)
                cached = self.files.get(p)
                if cached is None or cached[0] != key:
                    cached = (key, self.parse_file(p))
                    self.files[p] = cached
                out.extend(cached[1])
        # Only the latest run: the steps never go back within one.
        start = 0
        for i in range(1, len(out)):
            if out[i]['steps'] < out[i - 1]['steps'] - 50_000:
                start = i
        out = out[start:]
        out.sort(key=lambda u: u['steps'])
        return out


# ---- the paired checks -------------------------------------------------------------------------------------------------


def num(text):
    """A number from the checks table; None for nan (one pair has no spread), which JSON cannot carry."""
    v = float(text)
    return v if v == v and v not in (float('inf'), float('-inf')) else None


def parse_checks(run):
    path = os.path.join(run, 'school-checks.log')
    if not os.path.exists(path):
        return []
    checks = []
    cur = None
    for line in open(path, encoding='utf8', errors='replace'):
        line = line.rstrip('\n')
        if line.startswith('=== '):
            m = re.match(r'=== (\d\d-\d\d \d\d:\d\d) (.*)', line)
            if not m:
                cur = None
                continue
            head = m.group(2)
            kind = 'school network'
            if 'TEACHER' in head:
                inner = re.search(r'TEACHER check \(([^:]*)', head)
                what = inner.group(1) if inner else ''
                kind = 'teacher over installed' if 'overlay' in what else 'teacher alone'
                fam = re.search(r'families ([\d,]+)', what)
                if fam:
                    kind += ' (families ' + fam.group(1) + ')'
            steps = re.search(r'\((\d+) steps so far\)', head)
            stage = re.search(r'at (T-[A-Z])', head)
            cur = {'time': m.group(1), 'kind': kind, 'steps': int(steps.group(1)) if steps else None,
                   'stage': stage.group(1) if stage else '', 'bots': []}
            checks.append(cur)
        elif cur is not None and re.match(r'^\S', line) and not line.startswith('bot ') and not line.startswith('after'):
            cols = line.split()
            if len(cols) >= 10:
                try:
                    row = {'bot': cols[0], 'pairs': int(cols[1]), 'aNet': num(cols[2]), 'bNet': num(cols[3]),
                           'aMinusB': num(cols[4]), 'se': num(cols[5]), 'aKo': cols[6], 'bKo': cols[7],
                           'aDt': cols[8], 'bDt': cols[9]}
                except ValueError:
                    continue
                if row['bot'] == 'ALL':
                    if 'all' not in cur:
                        cur['all'] = row
                else:
                    cur['bots'].append(row)
        elif cur is not None and line.startswith('  A jumping in:'):
            cur['aJumpIn'] = line.split(':', 1)[1].strip()
        elif cur is not None and line.startswith('  B jumping in:'):
            cur['bJumpIn'] = line.split(':', 1)[1].strip()
    return [c for c in checks if 'all' in c and c['all']['aMinusB'] is not None]


# ---- what the page gets ------------------------------------------------------------------------------------------------


def build(cache, techniques, stages, prio):
    ups = cache.updates()
    stage_of = {}
    for s in stages:
        if s['name'] == 'T-F':
            continue
        for tid in s['techniques']:
            stage_of.setdefault(tid, s['name'])
    # Per technique: its rate over its last WINDOW moments after every update.
    hist = {t['id']: [] for t in techniques}
    series = {t['id']: [] for t in techniques}
    for u in ups:
        for tid, (d, n, paid) in u.get('done', {}).items():
            if tid in hist and n > 0:
                hist[tid].append((d, n, paid))
        for tid, h in hist.items():
            if not h:
                continue
            d = n = paid = 0
            for dd, nn, pp in reversed(h):
                d += dd
                n += nn
                paid += pp
                if n >= WINDOW:
                    break
            if n >= MIN_MOMENTS and tid in u.get('done', {}):
                series[tid].append([u['steps'], round(100.0 * d / n, 1)])
    last = ups[-1] if ups else {}
    rows = []
    for t in techniques:
        tid = t['id']
        if tid == '-':
            continue  # the ordinary fight: the installed model's to teach since the overlay
        h = hist[tid]
        d = n = paid = 0
        for dd, nn, pp in reversed(h):
            d += dd
            n += nn
            paid += pp
            if n >= WINDOW:
                break
        taught = None
        for u in reversed(ups[-6:]):
            if tid in u.get('taught', {}):
                taught = u['taught'][tid][1]
                break
        # The last hour of training against the hour before it (by steps), so a plateau shows: the 40-moment rate alone
        # swings by about 8 points either way at 50% from chance.
        change = None
        stuck = False
        sr = series[tid]
        if sr:
            end = ups[-1]['steps']
            recent = [y for x, y in sr if end - HOUR < x <= end]
            prior = [y for x, y in sr if end - 2 * HOUR < x <= end - HOUR]
            if len(recent) >= 5 and len(prior) >= 5:
                change = round(sum(recent) / len(recent) - sum(prior) / len(prior), 1)
                stuck = sum(recent) / len(recent) < 85 and change < STUCK_GAIN
        rows.append({'id': tid, 'title': t['title'], 'stage': stage_of.get(tid, '-' if tid == '-' else ''),
                     'priority': tid in prio, 'moments': n, 'rate': round(100.0 * d / n, 1) if n >= MIN_MOMENTS else None,
                     'paid': round(100.0 * paid / n, 1) if n >= MIN_MOMENTS else None, 'taught': taught,
                     'change': change, 'stuck': stuck, 'series': series[tid][-300:]})
    damage = [[u['steps'], u['dealt'], u['taken'], u['time']] for u in ups if 'dealt' in u]
    checks = parse_checks(cache.run)
    age = None
    log = os.path.join(cache.run, 'school-client.log')
    if os.path.exists(log):
        age = time.time() - os.path.getmtime(log)
    state = 'training'
    if os.path.exists(log):
        tail = open(log, encoding='utf8', errors='replace').read()[-20000:]
        if 'school pair' in tail[-4000:]:
            state = 'paired check running'
    return {
        'now': {k: last.get(k) for k in ('time', 'steps', 'stage', 'family', 'stageSteps', 'minSteps', 'installedPull',
                                         'weakest', 'dealt', 'taken', 'keys', 'game', 'jumpIn', 'crit', 'update', 'decider')},
        'logAgeSeconds': age, 'state': state,
        'stages': [s for s in stages if s['name'] != 'T-F'], 'priority': prio,
        'techniques': rows, 'damage': damage[-600:], 'checks': checks,
        'served': time.strftime('%H:%M:%S'),
    }


def local_ip():
    """This machine's address on the local network (no packet is sent)."""
    import socket
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('192.0.2.1', 9))
        return s.getsockname()[0]
    except OSError:
        return '127.0.0.1'
    finally:
        s.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--run', default=os.path.join(REPO, 'run'))
    ap.add_argument('--port', type=int, default=8765)
    # Every address of this machine by default, so it opens from the local network (and, port-forwarded, from outside);
    # it only ever serves the page and its data, read-only.
    ap.add_argument('--host', default='0.0.0.0')
    a = ap.parse_args()
    techniques = read_techniques()
    stages, prio = read_stages(techniques)
    cache = LogCache(a.run)
    page = os.path.join(HERE, 'index.html')

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path.startswith('/api/data'):
                try:
                    body = json.dumps(build(cache, techniques, stages, prio), allow_nan=False).encode('utf8')
                    self.send(200, 'application/json', body)
                except Exception as e:  # the page shows it instead of going blank
                    self.send(500, 'application/json', json.dumps({'error': repr(e)}).encode('utf8'))
            elif self.path in ('/', '/index.html'):
                self.send(200, 'text/html; charset=utf-8', open(page, 'rb').read())
            else:
                self.send(404, 'text/plain', b'not found')

        def send(self, code, ctype, body):
            self.send_response(code)
            self.send_header('Content-Type', ctype)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer((a.host, a.port), Handler)
    print(f'SwordAI school panel on http://localhost:{a.port} and http://{local_ip()}:{a.port} (reading {a.run})', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
