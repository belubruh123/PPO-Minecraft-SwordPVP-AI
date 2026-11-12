"""oppdemo/run.py OUTDIR [--seed N]: the whole stage-1 pipeline. Python 3.8, standard library only.

  C:/Python38/python.exe -I tools/oppdemo/run.py <scratch dir> [--seed 1]

Reads the real duels (read only), writes OUTDIR/oppdemos.json and OUTDIR/stats.json (numbers for REPORT.md). Prints counts only,
never names. See lib.py for the recording facts and the definitions.
"""
import collections
import json
import os
import random
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))   # python -I does not add the script's folder
import lib  # noqa: E402

MAIN_SEED = 1
ALT_SEEDS = (2, 3, 4, 5, 6)
MIN_EX, MIN_ROUNDS = 5, 2           # a bin needs 5 good examples from 2 different rounds (the brief)
MIN_TRAIN_SHARE = 0.5               # the winning action: at least half of the bin's good moves
MIN_HELD_N = 10                     # held-out samples with / without the action for an entry to be judged
BOOT = 400


# ---- samples --------------------------------------------------------------------------------------------------------------
def collect(rounds, dmg):
    """Per-tick samples of the lost rounds, both actors: (duel, round id, who, bin, action, net) where the action is known and
    the 40-tick window was reached (or the round ended with our death inside it, which is the outcome)."""
    out = []
    for ri, r in enumerate(rounds):
        for who in ('opp', 'me'):
            S = lib.side_data(r, who)
            for t in range(r.a + 1, r.b):
                if S.act[t] is None or S.feat[t] is None:
                    continue
                net, full = lib.net_hp(r, t, dmg, who)
                if not full and r.why != 'death':
                    continue
                out.append((r.duel, ri, who, S.feat[t], S.act[t], net))
    return out


def examples(rounds, dmg):
    """Training examples of the OPPONENT's actions in these rounds (a run of a move label counts once, at its start, and must last
    3+ ticks; every swing and jump onset counts). Returns a list of dicts and the drop-reason counts of candidate moves."""
    ex = []
    drops = collections.Counter()
    for ri, r in enumerate(rounds):
        S = lib.side_data(r, 'opp')
        for t in range(r.a + 1, r.b):
            a = S.act[t]
            if a is None or S.feat[t] is None:
                continue
            if a in ('FWD', 'BACK', 'STILL'):
                if S.act[t - 1] == a:
                    continue
                if not (t + 2 < r.b and S.act[t + 1] == a and S.act[t + 2] == a):
                    continue
            good, why = lib.is_good_opp(S, t, dmg)
            if not good:
                drops[why] += 1
            ex.append(dict(duel=r.duel, rid=(r.duel, ri), act=a, good=good, why=why,
                           det=S.det[t], conf=S.conf[t], feat=S.feat[t]))
    return ex, drops


# ---- the table -------------------------------------------------------------------------------------------------------------
def build_table(ex, level):
    """bin -> entry, kept only with support: 5+ good examples from 2+ rounds, one action winning (plurality of the good moves, at
    least half of them, the modal action of a majority of the supporting rounds) and doing better than the other actions of
    the same bin in the training set (success rate of the action over its own examples vs the others')."""
    by = collections.defaultdict(list)
    for e in ex:
        by[lib.bin_key(e['feat'], level)].append(e)
    table, dropped = {}, collections.Counter()
    for b, es in by.items():
        g = [e for e in es if e['good']]
        rounds = set(e['rid'] for e in g)
        if len(g) < MIN_EX:
            dropped['fewer than %d good examples' % MIN_EX] += 1
            continue
        if len(rounds) < MIN_ROUNDS:
            dropped['good examples from fewer than %d rounds' % MIN_ROUNDS] += 1
            continue
        cnt = collections.Counter(e['act'] for e in g)
        win, wn = cnt.most_common(1)[0]
        if wn / len(g) < MIN_TRAIN_SHARE:
            dropped['no action has half of the good moves'] += 1
            continue
        per_round = collections.defaultdict(collections.Counter)
        for e in g:
            per_round[e['rid']][e['act']] += 1
        modal = 0
        for rid, c in per_round.items():
            m = max(c.values())
            if c[win] == m:
                modal += 1
        if modal * 2 <= len(per_round):
            dropped['the winning action is not the modal one of most rounds'] += 1
            continue
        alln = collections.Counter(e['act'] for e in es)
        rate = lambda a: cnt[a] / alln[a] if alln[a] else 0.0
        oth_n = sum(alln[a] for a in alln if a != win)
        oth_g = sum(cnt[a] for a in cnt if a != win)
        rate_other = oth_g / oth_n if oth_n else 0.0
        if rate(win) <= rate_other:
            dropped['winning action no better than the others in its bin'] += 1
            continue
        det_sprint = sum(1 for e in g if e['act'] == win and e['det'] and e['det'].get('sprint'))
        det_crit = sum(1 for e in g if e['act'] == win and e['det'] and e['det'].get('crit'))
        table[b] = dict(
            action=win,
            p_good={a: round(cnt[a] / len(g), 3) for a in lib.ACTIONS if cnt[a]},
            p_all={a: round(alln[a] / len(es), 3) for a in lib.ACTIONS if alln[a]},
            n_good=len(g), n_all=len(es), rounds=len(rounds), duels=len(set(e['duel'] for e in g)),
            success_rate_action=round(rate(win), 3), success_rate_others=round(rate_other, 3),
            winning_action_sprinting=round(det_sprint / max(1, wn), 2), winning_action_crit_swings=round(det_crit / max(1, wn), 2))
    return table, dropped


# ---- held-out validation ---------------------------------------------------------------------------------------------------
def validate(table, samples, level, who_filter=None):
    """Per action type X: in the bins where the table recommends X, the held-out ticks where the actor did X against ticks where
    it did something else (known actions only), net HP over 40 ticks from the actor's side. Effect = sum over bins of
    w_b (mean_X - mean_other), w_b = nX nO / (nX + nO) (a bin counts only with both groups). SE = cluster bootstrap over duels."""
    rec = {b: e['action'] for b, e in table.items()}
    # per duel, per (action type, bin): [nX, sumX, nO, sumO]
    agg = collections.defaultdict(lambda: collections.defaultdict(lambda: [0, 0.0, 0, 0.0]))
    for duel, ri, who, f, a, net in samples:
        b = lib.bin_key(f, level)
        if b not in rec or (who_filter and who != who_filter):
            continue
        X = rec[b]
        c = agg[duel][(X, b)]
        if a == X:
            c[0] += 1
            c[1] += net
        else:
            c[2] += 1
            c[3] += net

    def effect(duels):
        tot = collections.defaultdict(lambda: [0, 0.0, 0, 0.0])
        for d in duels:
            for k, c in agg[d].items():
                t = tot[k]
                for i in range(4):
                    t[i] += c[i]
        num, den, nx, no = collections.defaultdict(float), collections.defaultdict(float), collections.Counter(), collections.Counter()
        for (X, b), c in tot.items():
            nx[X] += c[0]
            no[X] += c[2]
            if c[0] >= 1 and c[2] >= 1:
                w = c[0] * c[2] / (c[0] + c[2])
                num[X] += w * (c[1] / c[0] - c[3] / c[2])
                den[X] += w
        return {X: (num[X] / den[X] if den[X] else None) for X in set(list(num) + list(nx))}, nx, no

    duels = sorted(agg)
    base, nx, no = effect(duels)
    rnd = random.Random(12345)
    boots = collections.defaultdict(list)
    for _ in range(BOOT):
        pick = [rnd.choice(duels) for _ in duels] if duels else []
        e, _, _ = effect(pick)
        for X, v in e.items():
            if v is not None:
                boots[X].append(v)
    out = {}
    for X in lib.ACTIONS:
        if X not in base:
            continue
        bs = boots.get(X, [])
        se = lib.sd(bs) if len(bs) > 10 else None
        out[X] = dict(n_did=nx[X], n_other=no[X], effect=None if base[X] is None else round(base[X], 3),
                      se=None if se is None else round(se, 3), duels=len(duels))
    return out


def entry_heldout(table, samples, level):
    """Each table entry on its own, held-out: n with / without the action, mean net each, effect (naive, ticks are correlated:
    only a hint, the action-type effect with its cluster standard error decides)."""
    res = {}
    for b, e in table.items():
        did, oth, duels = [], [], set()
        for duel, ri, who, f, a, net in samples:
            if lib.bin_key(f, level) != b:
                continue
            (did if a == e['action'] else oth).append(net)
            if a == e['action']:
                duels.add(duel)
        res[b] = dict(n_did=len(did), n_other=len(oth), duels_did=len(duels),
                      mean_did=round(lib.mean(did), 3) if did else None, mean_other=round(lib.mean(oth), 3) if oth else None,
                      effect=round(lib.mean(did) - lib.mean(oth), 3) if did and oth else None)
    return res


def usable_type(v):
    return bool(v and v['effect'] is not None and v['se'] is not None and v['effect'] - 1.64 * v['se'] > 0
                and v['n_did'] >= 30 and v['duels'] >= 5)


# ---- inference check, latency ---------------------------------------------------------------------------------------------------
def check_inference(all_rounds):
    """The same velocity inference run on OUR AI (whose keys are known) against what its keys really were: precision of the
    opponent's inferred FWD / BACK / still, by confidence, against the keys of the same row and 1 and 2 rows before (keys act
    a tick later), and the jump-onset rule against our jump key."""
    conf = collections.defaultdict(collections.Counter)
    for r in all_rounds:
        ep = r.ep
        for k in range(r.a + 3, r.b):
            if ep.meAir[k] or ep.meAir[k - 1] or any(ep.taken[j] for j in range(max(0, k - 8), min(ep.n, k + 2))):
                continue
            s = 0.5 * (ep.myToward[k] + ep.myToward[k - 1])
            sprint = (ep.keys[k] + '-----')[2] == 'r'
            if s >= 0.18:
                inf = 'FWD hi'
            elif s >= lib.V_MOVE:
                inf = 'FWD med'
            elif s <= -0.16:
                inf = 'BACK hi'
            elif s <= -lib.V_MOVE:
                inf = 'BACK med'
            elif abs(s) < lib.V_STILL and not sprint:
                inf = 'STILL (not used)'
            else:
                continue
            for lag in (0, 1, 2):
                kk = (ep.keys[k - lag] + '-----')[:5]
                real = 'FWD' if kk[0] == 'W' else ('BACK' if kk[0] == 'S' else 'STILL')
                conf[lag][(inf, real)] += 1
    out = {}
    for lag in (0, 1, 2):
        c = conf[lag]
        res = {}
        for inf in ('FWD hi', 'FWD med', 'BACK hi', 'BACK med', 'STILL (not used)'):
            tot = sum(v for (i, rl), v in c.items() if i == inf)
            res[inf] = dict(n=tot, precision=round(c[(inf, inf.split()[0])] / tot, 3) if tot else None)
        out['keys_%d_rows_before' % lag] = res
    tp = fp = 0
    for r in all_rounds:
        ep = r.ep
        for k in range(r.a + 3, r.b - 3):
            if not ep.meAir[k - 1] and ep.meAir[k] and not any(ep.taken[j] for j in range(max(0, k - 8), min(ep.n, k + 2))):
                if any((ep.keys[j] + '-----')[3] == 'j' for j in range(k - 2, k + 1)):
                    tp += 1
                else:
                    fp += 1
    out['jump_onset_precision_vs_our_jump_key'] = round(tp / (tp + fp), 3) if tp + fp else None
    out['jump_onset_n'] = tp + fp
    return out


def latency(duel_eps):
    """Per duel: median swing-to-hit ticks of our hits (the round trip, bench Episode.hits), the ping in ticks, and the ticks from
    our DEALT to the opponent's lift (oppUp rising from the ground = its knockback reaching the recording)."""
    rtt, png, kbo = [], [], []
    per = []
    for duel, ep, path in duel_eps:
        d = [h.d - h.u for h in ep.hits if h.u is not None]
        p = lib.ping_ms(path)
        offs = []
        for k in range(1, ep.n - 12):
            if ep.dealt[k] and not ep.oppAir[k - 1] and not ep.oppAir[max(0, k - 3)]:
                for j in range(-3, 13):
                    q = k + j
                    if 0 < q < ep.n - 1 and (not ep.oppAir[q - 1]) and ep.oppAir[q] and max(ep.oppUp[q:q + 3]) > 0.1:
                        offs.append(j)
                        break
        if len(d) >= 4:
            rtt.append(lib.mean(sorted(d)[len(d) // 2:len(d) // 2 + 1]))
            if p is not None:
                png.append(p / 50.0)
                per.append((sorted(d)[len(d) // 2], p / 50.0))
        if len(offs) >= 3:
            kbo.append(sorted(offs)[len(offs) // 2])
    return rtt, png, kbo, per


def median(xs):
    xs = sorted(xs)
    return xs[len(xs) // 2] if xs else None


# ---- main ---------------------------------------------------------------------------------------------------------------------
def funnel(rounds, dmg):
    """What each filter removes, counted on the opponent's HITS (TAKEN rows): all hits, trades (we hit within 4 ticks either side),
    the rule breakers (target jumper), hit back with no combo, then net HP >= 1, 2, 3 over the next 40 ticks."""
    f = collections.Counter()
    for r in rounds:
        ep = r.ep
        S = lib.side_data(r, 'opp')
        for h in range(r.a + lib.START_SKIP, r.b):
            if not ep.taken[h] or S.tele[h]:
                continue
            f['1 hits'] += 1
            if any(ep.dealt[k] for k in range(max(r.a, h - lib.TRADE), min(r.b, h + lib.TRADE + 1))):
                continue
            f['2 not a trade'] += 1
            if any(S.oth_air_run[k] >= lib.AIR_GROUND_TICK for k in range(max(r.a, h - 3), h + 1)):
                continue
            f['3 target not a jumper (rule)'] += 1
            back = any(ep.dealt[k] for k in range(h + 1, min(r.b, h + 1 + lib.NO_HIT_BACK)))
            combo = False
            for k in range(h + 1, min(r.b, h + 1 + lib.COMBO_GAP)):
                if ep.dealt[k]:
                    break
                if ep.taken[k]:
                    combo = True
                    break
            if back and not combo:
                continue
            f['4 no hit back or combo'] += 1
            n = lib.net_hp(r, h - 1, dmg, 'opp')[0]
            for th in (1, 2, 3):
                if n >= th:
                    f['5 net >= %d' % th] += 1
    return dict(sorted(f.items()))


def descriptive(pool, dmg):
    """The kept-by-the-filter moves of a whole pool (no split, no support gate): the top bins (level 2) by good moves with the
    action mix, and tags of the good swings. NOT a table: only what the filter finds."""
    ex, drops = examples(pool, dmg)
    good = [e for e in ex if e['good']]
    by = collections.defaultdict(list)
    for e in good:
        by[lib.bin_key(e['feat'], 2)].append(e)
    top = []
    for b, es in sorted(by.items(), key=lambda kv: -len(kv[1]))[:10]:
        c = collections.Counter(e['act'] for e in es)
        allc = sum(1 for e in ex if lib.bin_key(e['feat'], 2) == b)
        top.append(dict(bin=b, good=len(es), of_all_examples=allc, rounds=len(set(e['rid'] for e in es)), actions=dict(c),
                        mean_dist=round(lib.mean([e['feat']['dist'] for e in es]), 2)))
    tags = collections.Counter()
    for e in good:
        d = e['det'] or {}
        if e['act'] == 'ATK':
            tags['ATK sprint hit' if d.get('sprint') else ('ATK crit swing' if d.get('crit') else 'ATK plain')] += 1
    return dict(good=len(good), examples=len(ex), top_bins=top, atk_kinds=dict(tags), drops=dict(drops))


def run_pool(name, pool, dmg, seed):
    """One pool of rounds (lost rounds only, or all rounds), for each bin level: the main split and the alternative splits
    (each split: shuffle the duels with the seed, first half trains, second half is held out)."""
    results = {}
    keep = {}
    for sd_ in (seed,) + ALT_SEEDS:
        tr_d, te_d = lib.split_duels(set(r.duel for r in pool), sd_)
        tr = [r for r in pool if r.duel in tr_d]
        te = [r for r in pool if r.duel in te_d]
        ex, drops = examples(tr, dmg)
        samples = collect(te, dmg)
        for level in (1, 2, 3):
            table, dropped_bins = build_table(ex, level)
            val_all = validate(table, samples, level)
            key = 'seed%d_level%d' % (sd_, level)
            results[key] = dict(train_duels=len(tr_d), test_duels=len(te_d), train_rounds=len(tr), test_rounds=len(te),
                                train_examples=len(ex), train_good=sum(1 for e in ex if e['good']), bins=len(table),
                                drops=dict(drops), dropped_bins=dict(dropped_bins), val_both=val_all,
                                val_opp=validate(table, samples, level, 'opp'), val_me=validate(table, samples, level, 'me'),
                                usable_types=[X for X, v in val_all.items() if usable_type(v)], held_samples=len(samples))
            if sd_ == seed:
                keep[level] = (table, ex, entry_heldout(table, samples, level))
            print(name, key, 'rounds tr/te', len(tr), len(te), 'good', results[key]['train_good'], '/', len(ex),
                  'bins', len(table), dict(dropped_bins), 'usable', results[key]['usable_types'])
            for X, v in val_all.items():
                print('       ', X, v)
    return results, keep


def main(argv):
    out = argv[0]
    seed = MAIN_SEED
    if '--seed' in argv:
        seed = int(argv[argv.index('--seed') + 1])
    global MIN_EX
    if '--min-ex' in argv:
        MIN_EX = int(argv[argv.index('--min-ex') + 1])
    if '--min-net' in argv:
        lib.MIN_NET = float(argv[argv.index('--min-net') + 1])
    os.makedirs(out, exist_ok=True)
    duels = lib.load_all()
    eps = [e for _, e, _ in duels]
    dmg = lib.estimate_dmg(eps)
    rounds = []
    for idx, ep, res in duels:
        rounds.extend(lib.split_rounds(ep, idx, res == 'won'))
    lib.judge_race(rounds, dmg)
    cnt = collections.Counter((r.outcome, r.why) for r in rounds)
    lost = [r for r in rounds if r.outcome == 'lost']
    stats = dict(duels=len(duels), ticks=sum(e.n for e in eps), dmg_per_hit=round(dmg, 3), min_net=lib.MIN_NET,
                 opp_hp_visible_duels=sum(1 for e in eps if e.opp_visible), rounds=len(rounds),
                 rounds_by_outcome={'%s/%s' % k: v for k, v in sorted(cnt.items())},
                 lost_rounds=len(lost), lost_rounds_duels=len(set(r.duel for r in lost)))
    print('duels %d rounds %d lost %d (duels with a lost round %d)' % (stats['duels'], stats['rounds'], len(lost), stats['lost_rounds_duels']))
    print(stats['rounds_by_outcome'])
    stats['inference_vs_own_keys'] = check_inference(rounds)

    pools = {'lost_rounds': lost, 'all_rounds': rounds}
    stats['pools'] = {}
    keeps = {}
    for name, pool in pools.items():
        stats['pools'][name], keeps[name] = run_pool(name, pool, dmg, seed)
    stats['main_seed'] = seed
    stats['funnel_lost'] = funnel(lost, dmg)
    stats['funnel_all'] = funnel(rounds, dmg)
    stats['descriptive_all'] = descriptive(rounds, dmg)
    stats['descriptive_lost'] = descriptive(lost, dmg)

    duel_eps = [(i, e, e.path) for i, e, _ in duels]
    rtt, png, kbo, per = latency(duel_eps)
    stats['latency'] = dict(duels_with_rtt=len(rtt), median_swing_to_hit_ticks=median(rtt), median_ping_ticks=median(png),
                            duels_with_kb=len(kbo), median_hit_to_lift_ticks=median(kbo),
                            swing_to_hit_minus_ping_ticks_median=median([a - b for a, b in per]))
    print(stats['latency'])

    table_out = {}
    for name in pools:
        for level in (1, 2, 3):
            table, ex, held = keeps[name][level]
            res = stats['pools'][name]['seed%d_level%d' % (seed, level)]
            types_ok = set(res['usable_types'])
            rec = {}
            for b, e in sorted(table.items()):
                h = held[b]
                e = dict(e)
                e['heldout'] = h
                e['usable'] = bool(e['action'] in types_ok and h['effect'] is not None and h['effect'] > 0
                                   and h['n_did'] >= MIN_HELD_N and h['n_other'] >= MIN_HELD_N and h['duels_did'] >= 2)
                rec[b] = e
            good = [e for e in ex if e['good']]
            if level == 1:
                stats['pools'][name]['main_examples'] = dict(
                    total=len(ex), good=len(good), by_action_all=dict(collections.Counter(e['act'] for e in ex)),
                    by_action_good=dict(collections.Counter(e['act'] for e in good)),
                    by_conf=dict(collections.Counter(e['conf'] for e in ex)),
                    good_why=dict(collections.Counter(e['why'] for e in good)))
            table_out['%s/level%d' % (name, level)] = dict(
                usable_action_types=sorted(types_ok), validation_both_sides=res['val_both'], entries=rec)
            print(name, 'level', level, 'entries', len(rec), 'usable', sum(1 for e in rec.values() if e['usable']))
    with open(os.path.join(out, 'oppdemos.json'), 'w') as f:
        json.dump(dict(
            meta=dict(note='STAGE 1, offline only. bin -> opponent action table from the opponents GOOD moves (train half by duel), with held-out checks on the other half. No names. An entry is usable only when usable is true.',
                      pools={'lost_rounds': 'rounds our AI lost (the brief)', 'all_rounds': 'every round: the opponent also wins exchanges inside rounds we win (more data)'},
                      bins=lib.BIN_DOC, actions=list(lib.ACTIONS),
                      action_notes={'ATK': 'its swing (oppSword fell), detail: sprint hit / crit swing', 'JUMP': 'onset of its air time with oppUp rising, not within 1 before to 8 after our DEALT (knockback)',
                                    'FWD': 'toward us (oppToward 2-tick mean >= 0.10) on the ground', 'BACK': 'away (<= -0.10) on the ground',
                                    'STILL': 'only for our AI (keys); NOT inferred for the opponent (precision ~15%)',
                                    'A/D': 'NOT inferable: oppSide is unsigned in the recording'},
                      main_seed=seed, constants=dict(window=lib.WINDOW, min_net=lib.MIN_NET, feature_lag=lib.FEATURE_LAG, no_hit_back=lib.NO_HIT_BACK,
                                                     trade=lib.TRADE, start_skip=lib.START_SKIP)),
            tables=table_out), f, indent=1, sort_keys=True)
    with open(os.path.join(out, 'stats.json'), 'w') as f:
        json.dump(stats, f, indent=1, sort_keys=True)


if __name__ == '__main__':
    main(sys.argv[1:])
