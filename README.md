# SwordAI

A Fabric mod for Minecraft 1.21.11 in which one neural network (pure Java, trained with PPO) plays 1.9+ sword PvP
in first person and fights real players.

- **Rules for working on it:** [AGENTS.md](AGENTS.md) (Claude Code reads it through CLAUDE.md)
- **How it is built and where it is going:** [docs/DESIGN.md](docs/DESIGN.md)
- **What was learned:** [docs/LESSONS.md](docs/LESSONS.md)
- **Run log:** [RUNS.md](RUNS.md)

## Use
- Build: `gradlew build` (the mod jar ends up in `build/libs`). Core tests: `gradlew :core:test`.
- Train: double-click `start-training.cmd`, which opens the dashboard at http://localhost:8765. Stop with
  `stop-training.cmd`. Close your own Minecraft first.
- Play: in game, `/swordai fight <player>`; `/swordai stop` ends it.

## Layout
- `core/`: the network, PPO and fight model, with no Minecraft code, so it can be unit-tested.
- `src/main`: the server side (arena, bots, combat log). `src/client`: sessions, commands, screen, mixins.
- `tools/`: the training start/stop and watchdog, the head-to-head check, real-duel reading, and the dashboard.
- `run/`: not in git. The dev game folder: checkpoints, recorded rounds, logs.
