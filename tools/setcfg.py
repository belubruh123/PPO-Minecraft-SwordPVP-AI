"""Sets fields of the dev run's config.json: python setcfg.py key=value ... ; a key 'Crits.jumperShare' is a field of the stage of that name."""
import json
import sys

P = 'D:/swordai/run/swordai/config.json'
cfg = json.load(open(P, encoding='utf-8'))


def conv(old, text):
    if isinstance(old, bool):
        return text.lower() in ('1', 'true', 'yes', 'on')
    if isinstance(old, int):
        return int(text)
    if isinstance(old, float):
        return float(text)
    return text


def newvalue(text):
    if text.lower() in ('true', 'false'):
        return text.lower() == 'true'
    try:
        return float(text)
    except ValueError:
        return text


for arg in sys.argv[1:]:
    if '=' not in arg:
        key = arg
        if '.' in key:
            name, field = key.split('.', 1)
            st = [s for s in cfg['stages'] if s['name'] == name][0]
            print(key, '=', st[field])
        else:
            print(key, '=', cfg[key])
        continue
    key, value = arg.split('=', 1)
    if '.' in key:
        name, field = key.split('.', 1)
        st = [s for s in cfg['stages'] if s['name'] == name][0]
        target = st
        parts = field.split('.')
        for part in parts[:-1]:
            target = target[part]
        target[parts[-1]] = conv(target.get(parts[-1], 0.0), value)
        print(key, '->', target[parts[-1]])
    else:
        cfg[key] = conv(cfg[key], value) if key in cfg else newvalue(value)
        print(key, '->', cfg[key])
json.dump(cfg, open(P, 'w', encoding='utf-8', newline=''), indent=2)
