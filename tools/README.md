# Training and test scripts

The scripts that run SwordAI's training and checks on the dev client (`gradlew runClient`, world `devworld`, game folder `run/`), copied from the working folder they run in. They talk to the client through `run/swordai/inbox.txt` (`swordai train`, `swordai stop`) and set `run/swordai/config.json` with `setcfg.py`. Paths are written out in full for the machine they ran on.

The ones in use on 23 Oct 2026:

- `trainGT7.sh`, `trainGT8.sh`: one branch's training settings (T7: T5 + crit/escape bridge; T8: T7 + RhythmNet memory); `startT7.sh`, `startT8.sh` start a branch with its hourly loop.
- `hourlycheck3.sh`: every 40 minutes of training, a snapshot `run/kept-v24-HHMM` is measured frozen, first person, against the installed network (`hourlysum2.py` writes the line to `hourly-check.log`); two WORSE hours roll training back.
- `hourlycheck4.sh`, `hourlysum3.py`, `pairrun.sh`, `pairsum.py`: the same with paired runs (both networks fight every fight from the same seed in one run, `Config.pairedBase`).
- `finalpair2.sh`, `fp2sum.py`, `fpairs.py`: the long check before an install; `install3.ps1` installs a network and jar into the player's profile, with a backup.
- `playguard6.sh`: pauses training while the player's own game is open, then takes it up again; `demowins.py` keeps the player's won recorded fights.
- `combopair.sh`, `combowin.py`, `combowhy.py`: the combo-escape rule A/B (rejected, 23 Oct).
- `report.py`: damage dealt/taken a minute per opponent from the client log between two times.
