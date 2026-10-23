# SwordAI

A Fabric mod for **Minecraft 1.21.11** that trains a reinforcement-learning agent to play sword PvP,
inside the game, from what it sees on screen.

## Setup, step by step

### What you need

| | |
|---|---|
| Minecraft | Java Edition **1.21.11** |
| Mod loader | **Fabric Loader** 0.19.5 or newer for 1.21.11 |
| This mod | `swordai-<version>+1.21.11.jar` |
| Anything else | **Nothing.** Fabric API and Carpet (the sparring bot's body) are inside the jar. Java comes with the launcher. |

Optional: Sodium, for more frames per second. Not compatible: shader packs (they repaint the colours the
agent's vision relies on) and resource packs that recolour players.

If you already have Fabric API or Carpet for 1.21.11 in your mods folder, leave them: the loader simply
uses the newest copy.

### Install

1. Start vanilla Minecraft 1.21.11 once from the launcher, then close it.
2. Download the Fabric installer from <https://fabricmc.net/use/installer/> and run it. On the *Client*
   tab choose Minecraft version **1.21.11**, keep the suggested loader version (0.19.5 or newer), press
   *Install*.
3. Open your mods folder: press `Win + R`, enter `%appdata%\.minecraft\mods`. Create the `mods` folder if
   it is not there. (On a Mac: in Finder press `Cmd + Shift + G` and enter
   `~/Library/Application Support/minecraft/mods`.)
4. Copy `swordai-<version>+1.21.11.jar` into it. If an older `swordai-*.jar` is there, delete it: two
   versions at once will not load.
5. In the launcher pick the **fabric-loader-1.21.11** profile and press *Play*.

### First run

1. *Singleplayer* > *Create New World*. Game mode **Creative**, world type **Superflat**. Any world works
   as long as you are in the Overworld; a throwaway world is simply the cleanest.
2. Recommended: play **windowed**, around 1280x720. A smaller picture means more frames per second, and
   the agent only needs a small one.
3. Press `T` and type `/swordai train`.

What happens next:

- You are moved to a stone arena high in the sky and a player called **SwordBot** appears, drawn in
  blue and green.
- A vision self-test runs for a few seconds (the bot is placed at known spots).
- The agent starts playing. The crosshair, hotbar and chat stay on screen; your mouse and keyboard
  are ignored so they cannot disturb it. Only `T`, `/` and `Esc` still do something.
- Every half minute or so it stops for a second or two: that is a learning update.

Training keeps running while you type in the chat and while you use other windows. Windowed mode with
the game left open behind your other windows is the safe choice. A minimised window is meant to work
too; if the game stops drawing there, the agent waits and carries on when the window is back, and the
report says how often that happened. On a handheld keep it plugged in and stop it from going to sleep.

### Watching, pausing, stopping

- `/swordai status` shows the stage, steps and what is still missing for the next stage. The window title is left alone.
- `Esc` pauses the agent and gives you the mouse and keyboard. Closing the menu resumes.
- `T` or `/` opens the chat without pausing, so `/swordai status` and the other commands can be used
  at any time.
- `/swordai stop` saves, removes the bot, and restores your inventory, game mode, game rules and options.
  The arena itself is left standing, so that the next start near it is at once instead of building it
  again; it is taken out of the world when you leave the world. Quitting the world also saves and restores
  everything. If the game is killed mid-run, the options are put back the next time it starts.
- `/swordai arena <radius>` sets how far the arena's floor reaches from its middle (20 to 32 blocks; it
  starts at 20, a floor of 41 by 41). Most fights start a few steps apart; one in five starts up to 26
  blocks apart, which is what the room is for (0.14.0; before that every fight began within ten blocks
  and the arena started at 12). The AI's picture shows the opponent and nothing else, so the size
  changes nothing about what it sees.
- `/swordai train` later continues from the last checkpoint. Training runs in real time, 20 decisions a
  second, so expect hours, not minutes. *Training faster* below has two ways to shorten that.

### Training faster

Training on the screen in real time is the reference: it is how the AI will play, on the picture it will
play with. There are two ways to go faster, and they use the **same checkpoint**, so you can switch between
all three whenever training is stopped.

**1. `/swordai speed <times>`, then `/swordai train`** - the same training on the screen, with the whole
game run faster (1 to 8 times).

- Everything in a fight is counted in ticks, so a fight goes the same way at any speed. The game lets a
  server tick faster but never its own client; the mod lifts that while it trains.
- Every decision still needs a freshly drawn frame, and a tick is now shorter. So the window is made
  small while it trains and put back when it stops. Nothing is lost by that: the AI's picture is made from
  270 rows of the frame however large the window is. `speedWindowRows` in `config.json` makes the window
  larger if you want to read it.
- If the computer does not keep up (more than one decision in ten on a frame already used, or the server
  running out of time for a tick), the speed comes down a step by itself and says so.
- `/swordai speed 1` goes back to real time. `/swordai speed` alone says what it is set to.

**2. `/swordai train headless`** - training without the screen.

- The AI plays a fake player of its own (`SwordAgent`) in an arena above you. Your character, keys, mouse
  and inventory stay yours, and the window can be minimised.
- Its picture is not read off the screen but drawn for it, and its body is run by the rules of a real game
  client. This is exactly the body it has in `/swordai spar`. Since nothing has to be rendered, the world
  runs as fast as the computer manages. It finds that speed by itself and tries for more again from time
  to time. Most of a tick is the game running the world around you, not the AI, so while it trains this
  way the render and simulation distance are set to 5 chunks (and put back afterwards); a flat, empty
  world is faster still.
- You can leave it. It says where it has got to every ten minutes and at every new stage, and
  `/swordai status` and `/swordai report` work as always.
- **It is the less faithful of the two.** The drawn picture is close to the real one, not identical (the
  report's `picture:` line says how close, as measured on your screen). A network trained only this way may
  pick up habits that fit the drawing. So:
  - To go back to the screen: `/swordai stop`, then `/swordai train`. It carries on from the same network,
    and a stretch on the screen is the test of what the other training was worth.
  - When it takes over from training on the screen, a copy of everything learned so far is kept in
    `swordai/before-headless`. `/swordai undo` puts that back (and keeps what it replaces in a `backup-...`
    folder), if the training without the screen turns out worse. (If it has learned from people since,
    undo first goes back to before that; see *Learning from people*.)
  - In the report the column `spd` shows the speed of every update, with an `h` in front of those trained
    without the screen, so the two can be told apart and compared.

To train on another computer, copy the whole `swordai` folder from the game folder of one to the other
(on a Mac: `~/Library/Application Support/minecraft/swordai`).

### Running it with nobody at the keyboard

Commands can be left for it in a file: write one or more `/swordai` commands, one per line, to
`<game folder>/swordai/inbox.txt`. While a world is open the file is looked at once a second, the
commands in it are run as if you had typed them, and the file is removed. What they say goes to the chat
and to `logs/latest.log`. Only `/swordai` commands are taken from there.

That is enough to train from a script: start the game with `--quickPlaySingleplayer <world folder>`,
put `swordai train headless` in the inbox, and later `swordai status`, `swordai report` or
`swordai stop`. While it trains, the log also gets one line per update (`fights since the last
update ...`) with what each kind of fight came to: its own fights, the fights against the opponent that
cannot be kept away, each crit drill, its fights by the opponent's connection (slow or ordinary), the
combo drills, and from the duel on the fights it still has against the scripted opponents of earlier
stages (the one yardstick that does not move while its copy does). Each also says how many swings it made, how many were sweeps, how many were early (none,
with the rule on), how many were made in the air and missed, how many of the hits it took it
answered with a swing, how many combos were landed on it and how many of those got a third hit, how
many it took without one swing back (and how many of those went to three hits or more), and on how
many of the ticks after a hit of a combo it held S.

### Sending results

`/swordai report` copies a text summary to the clipboard (and to `swordai/report.txt`). Paste it back.
If the agent stopped with an error, do that straight away. If the self-test fails, run `/swordai play`,
then `/swordai debug`, and send the PNG files from `.minecraft\swordai\debug`.

If the game does not start at all, send `.minecraft\logs\latest.log`.

### Updating the mod

Replace the jar and start the game. A checkpoint from an older version is upgraded when it is loaded,
so training carries on from where it was.

**0.16.0** (23 October): **the crit trade against a player jumping in**. An opponent seen taking off
at the fighter with its sword ready is no longer hit first from the top of its jump (its knockback comes
late on a server, so it would crit back): its crit is let land, and the fighter crits back on its way
down from that hit at once (`holdOnFallingJumper`, `instantPunish`, `holdOnRising` false). Only a real
jump in counts: one seen going up while coming at the fighter, not a hop in place, a jump made with a
hit it took, or a step down. In paired tests on the same network with and without these rules: in the
jump-in drill +1.44 damage a fight (a crit for a crit instead of 7 for 10.5), against the spacers +4 a
minute and the mix -4 (both level). The network is the same as in 0.15.4.

**0.15.3** (19 October, night): **safe learning in `/swordai fight`**. With `/swordai learn on`, the
proven network and a learning copy take turns, a fight each (knockout to knockout, or, where the
game never shows the knockout, until the opponent is gone or for a minute at most). Only the copy
learns, gently, and only from its own fights. It replaces the proven network once it has dealt more
damage and taken less (damage dealt minus taken a minute, at least 30 better) over 8 fights and
3 minutes each, and it is thrown away if it does 60 a minute worse after 3 fights each, or is no
better after 16. The copy and its score are kept in `swordai/learning.bin` and
`learning.properties`; a network replaced this way is kept, so `/swordai undo` brings it back. Turn
it off with `"safeLearn": false` in the config to learn straight into the network as before. In
training, every scripted fight it loses is kept as it was set up and fought again until it has won it
twice in a row; beaten ones are fought again later in case they were forgotten
(`replayLostShare`, `swordai/lost-cases.json`). The network is the same as in 0.15.2.

**0.15.2** (19 October): a day more of training, mostly against the opponents it loses to (the
spacer, the timer, the jump-crit chaser, the punisher) and the 0.14.0 network, paid for damage traded
rather than for hits, and more for combos. An opponent jumping in for a crit is met with a hit also
while the fighter is still being carried back by the last one (`meetWhenComing`). In a frozen test
against the network installed before (v19), damage dealt minus taken a minute: against the 0.14.0
network +77 (v19 +60), against the hard scripted opponents -24 on average (v19 -49). Three rules
were tried and are off because they cost more damage than they won: the crit back in the opponent's
cooldown (`counterCritRule`), stepping out of a jump-crit with the sword down (`dodgeJumpRule`) and
standing at the edge of a ready sword (`edgeHoldRule`).

**0.15.1** (the same evening): the fight key stops a fight as well as starting it; online the
network's swing waits for the sword all the way back again (thirteen ticks, as 0.14.0), since at
105 ms twelve gave weak hits; and no swing is made at an opponent plainly out of reach or off the
screen, which had handed opponents free hits (`whiffGuard`). The config file is regenerated
(version 63) and keeps your settings.

**0.15.0** gives the network one more input, how far back the opponent's sword is (read off its
arm), so a 0.14.0 checkpoint is upgraded when it is loaded and plays as it did until it learns to use
it. The config file is brought up to date and keeps your settings. New: the menu (Right Shift) with
import and export, the round recorder (`swordai/fights`: every round lost or won in `/swordai fight`,
with what the opponent did), running straight in when out of reach, and W-tap combos besides the
circling ones. The fighter trained for it against the players at the edge of the reach (see
`REQUIREMENTS.md`) and against the 0.14.0 network itself.

**0.14.0** changes how a fight is fought and what the crit stage teaches; see *What 0.14.0 found*
under *Curriculum*. For a run that is being carried on:

- A run that is in stage 8 **begins stage 8 again**, from its first drill and with the jump key started
  afresh; everything else it knows is kept. A run already in the duel or in self-play is left there, and
  `/swordai stage 8` puts it back by hand. That is worth doing: stage 8 is where it first meets the
  opponent on a slow connection and the one that cannot be kept off.
- `/swordai fight` and `/swordai spar` **no longer teach the AI** unless you ask for it with
  `/swordai learn on`, whatever that was set to before. If your network learned from fights on 0.13.2 or
  earlier and has been swinging early since, `/swordai undo` brings back the copy from before that.
- The arena is at least 20 blocks from its middle to its walls (it was 12), so the first start after
  the update builds a new one.

0.7.0 gives the network one more input (how many hits it has taken in a row). A checkpoint from 0.6.1 or
earlier plays exactly as it did until it learns to use that input. Once upgraded and saved it can no
longer be read by the older version, so the checkpoint as it was is kept as
`swordai/checkpoint-before-upgrade.bin`: to go back to the older jar, put that file back as
`checkpoint.bin`.

0.7.0 also changed what stages 7 and 8 teach, and 0.8.0 changes how, because in 0.7.0 neither worked: a
run got through stage 7 on its time limit having held S in 2% of the ticks that mattered, and in stage 8
it stopped jumping altogether within 20,000 steps (see *Curriculum*). So with 0.8.0:

- A run that is in stage 8 **goes back to stage 7** with everything it knows, and says so when training
  starts. `/swordai stage 8` skips that. A run that is already in the duel or in self-play is left there;
  `/swordai stage 7` puts it back by hand.
- When stage 8 begins, **the jump key starts afresh**: what the network had made of that one key is set
  aside, the rest of what it knows is untouched.

0.9.0 changes how stages 7 and 8 get their move tried at all, because with 0.8.0 it still held no S and
made no jumps: both were keys it had stopped pressing, and a key that is not pressed is never found to
help (see *Getting a move tried* under *Curriculum*). So with 0.9.0 a run in stage 7 **begins stage 7
again** and a run in stage 8 **goes back to stage 7**, with everything it knows; it says so when training
starts, and `/swordai stage 8` skips it.

0.10.0 replaces the stage 7 opponent, which made S worthless (see *Curriculum*). A run in stage 7 begins
stage 7 again, with the help for S from its start, and a run in stage 8 goes back to stage 7; both keep
everything they know.

0.13.0 to 0.13.3 change what stage 8 teaches about critical hits (see *When to crit, when not, and the
punish crit* under *Curriculum*). A run that is in stage 8 **begins stage 8 again**, from its first drill
and with the jump key started afresh; everything else it knows is kept. A run that is already in the duel
or in self-play is left there: **`/swordai stage 8` puts it back by hand, and that is the only way to give
it the new lessons.** A network that learned to jump under 0.12.3 or 0.13.0 jumps out of habit, and that
habit does not come out by fighting on; it has to start the jump key over, which is what beginning stage 8
does.

**If the AI holds S all the time after training on 0.9.0, 0.9.1 or 0.10.0**, that came from those
versions (see *Getting a move tried*). Install 0.10.1, then `/swordai stop`, `/swordai retry 7` and
`/swordai train`: the network goes back to how it was when it passed stage 6.

### Commands

| Command | What it does |
|---|---|
| `/swordai train` | Start or resume training. `train fresh` archives the old run and starts over. |
| `/swordai train headless` | The same training without the screen: the AI plays a fake player with a drawn picture, as fast as the computer manages. See *Training faster*. |
| `/swordai speed [times]` | Show, or set, how many times normal speed `/swordai train` runs the game at (1 to 8). |
| `/swordai undo` | Go back to the network as it was before training without the screen, or learning from people, last took over: whichever began last. That copy is then used up, so a second undo goes back one step further. |
| `/swordai learn on` / `off` | Whether `/swordai fight` and `/swordai spar` also teach the AI. Off unless you turn it on. See *Learning from people*. |
| `/swordai arena [radius]` | Show, or set, how far the arena's floor reaches from its middle, in blocks (20 to 32). |
| `/swordai name [nick]` | Show, or set, the opponent's name. A fake player gets the skin of the Minecraft account of that name, if there is one. You see it in `spar` and `train headless`, and on the screen too with `/swordai skin on`. |
| `/swordai skin on` / `off` | While the AI plays on your screen: show the opponent with its own skin, armour and name tag instead of the blue and green figure. The AI still gets the blue and green picture, drawn first in a pass of its own; the world is then drawn again for you. Two pictures per frame, so about half the frames a second, and the AI's picture is a little older for it. Off by default; for long training runs leave it off. Can be switched while it runs. **In 0.5.2 and 0.6.0 this left the AI blind on at least one computer (a Mac): its picture had no opponent in it, and it trained on that.** From 0.6.1 the AI's picture is read from a copy of its own, and the mod watches for a picture without the opponent: within two seconds it switches the skin off again, says so, and does not learn from that stretch. |
| `/swordai stop` | Save and give the game back. |
| `/swordai menu` | All of the everyday commands as buttons. Also on **Right Shift** (Controls → Key Binds → Miscellaneous to change it). A second key there, *SwordAI: start / stop fight*, starts `/swordai fight` against the nearest player or stops whatever runs; it has no key until you give it one. |
| `/swordai export [name]` | Save the current model (network, settings, where its training has got to: about 10 MB) as one `.zip` in `swordai/models`, to keep or to take to another game. |
| `/swordai import <name>` | Make a model in `swordai/models` the current one. What it replaces is saved there first as `before-import`, so `/swordai import before-import` takes it back. Not while anything is running. |
| `/swordai models` | List the models in `swordai/models`. |
| `/swordai retry [n]` | Learn stage `n` again (default: the current one), starting from the network as it was when the stage before it was passed. What it replaces is kept in a `backup-...` folder. |
| `/swordai report` | Summary to clipboard and `swordai/report.txt`. |
| `/swordai status` | Current stage and recent numbers. |
| `/swordai play` | Run the current policy without learning, to watch it. |
| `/swordai fight [player]` | The trained AI plays **your character** against another player, in any world or on any server you are in. See below. |
| `/swordai spar` | **You** fight the trained AI, which plays the fake player in the arena (singleplayer). See below. |
| `/swordai fov [degrees]` | Show, or set, the field of view of the picture the AI gets. Your screen is held at 90 either way. |
| `/swordai selftest` | Only run the vision check. |
| `/swordai debug` | Save what the agent sees (frame, masks, numbers) to `swordai/debug`. |
| `/swordai stage <n>` | Jump to a stage by hand. |

## Fighting with it and against it

Both use the saved network as it is and, unless you turn on `/swordai learn`, teach it nothing. It plays
with the controls of the stage it has reached, so before stage 5 it aims and moves but does not hit.

Until the duel stage the AI cannot lose health in its training: it is patched up every tick, so all it has
ever seen of its own health is a full bar with a dip on the tick it is hit. A real fight patches nobody up,
and a health bar that stays low is something it has never seen and cannot read. So until it has passed
into the duel, a fight or a sparring match shows it its health the way training does: full, with the same
dip when it is hit. From the duel on it sees its health as it is.

### `/swordai fight` - it plays your character

For a friend to fight it. Join the world or server where your opponent is, stand where you want to fight,
and type `/swordai fight` (or `/swordai fight <name>` if more than two players are around). The AI takes
over your mouse and keys, looks for the opponent and fights. It needs nothing from the server and changes
nothing on it.

- Have a sword on your hotbar; it puts it in your hand. Flat, open ground suits it best: it has only ever
  fought in a flat arena and hardly ever jumps.
- Only the player being fought is recoloured on your screen. Once it has an opponent it stays with that
  player; nobody else becomes a target.
- If nobody is there to fight (or they are dead, or a menu is open) you get the controls back until the
  fight can go on. After dying it respawns by itself.
- `Esc` pauses, `/swordai stop` ends it, restores your settings and prints the result: hits, kills, how
  often it was on target. The same line is kept for the next `/swordai report`.
- That line also says what its swings were, as your game made them out when it made them: before the
  sword was back, critical, with the sprint behind them, sweeps, plain. Since 0.14.0 the first of these
  is 0: it cannot swing before the sword is back, and on a server it waits a tick longer still (see
  *What 0.14.0 found*). If you see weak hits all the same, that line tells which it is: a number
  there means the rule is not holding; none there means the server counted the time between two
  swings shorter than your game did. For such a server set `swingExtraTicksOnline` in `config.json`
  to 1 or 2: it then waits that many ticks more once the sword is back.
- Only use it where everyone knows about it. On a public server this is an attack bot.

### `/swordai spar` - you fight it

In a singleplayer world. The arena is built as for training and you play your own character with your
own settings; SwordBot is played by the network. Both get the same kit. A knockout ends the round, the
next starts after a three-second count, and the score is said in chat after each round. `/swordai stop` ends it and
puts you and your inventory back.

The network learned on a real game client, and the fake player has none. So the fake player is run as if
it had one, tick by tick in the same order, by rules read off the game's own code:

- **Sprint.** A sprint starts with W and the sprint key (or W tapped twice), ends when W is let go, and a
  sprint hit ends it on the server while the client restarts it at once without saying so. The server
  therefore gives no knockback again until the sprint really stops for a tick. A click is judged with the
  sprint the server knew of before that tick's keys.
- **Clicks.** A click is judged right after the camera turns and before the body moves, against the
  opponent where a client would show it. A click that lands on the floor or a wall is swallowed, as in
  the game.
- **The opponent.** A client never has another player's true position: it gets one every second tick and
  glides to it over three. The fake player sees and hits that gliding copy, like any player online does.
- **The picture.** You are drawn as the game's own player model (head, body, arms, legs, with the game's
  walk cycle, sword swing, arm sway and crouch), from where the camera was part-way through the tick
  before, which is the frame a client would have had. It then goes through the same steps as a real
  frame. Not drawn: what you hold.
- **Choices.** It picks from the network's choices exactly as training does (`playTemperature` 1).

It still gets no distance and no reach. While the AI trains or plays your character, the mod keeps
drawing the same moments this way and compares them with the real frames; `/swordai report` shows how
close they are (the `picture:` line), and how old a real frame is when it is decided on.

When you stop, it says what its hits were: sprint knockback, crit, sweep, before the sword was charged, or
plain; how many swings reached you without doing damage (you were still flashing from the last hit); and
how many missed.

The only way to have it literally identical is a second game window: start the game twice, join your own
server with both, and type `/swordai fight` in one. That one is the AI on a real client, pixels and all.

### Learning from people

`/swordai learn on` makes `/swordai fight` and `/swordai spar` teach the AI as well. It stays on until
`/swordai learn off`. Nothing else about the two commands changes.

- **What it learns towards.** The rewards of the stage it is in, the same ones training uses. A person
  is a far better opponent than the scripted one, so this is where it meets what the stages cannot give
  it: someone who strafes, crits, runs, and fights at any distance in any arena.
- **It does not count towards a stage.** A person is not the stage's opponent, so nothing fought against
  one is counted towards passing a stage, and no stage is passed while fighting one.
- **Nothing stops.** About every minute and a half of fighting (more often before stage 6) it learns from what it has gathered. A
  fight cannot wait for that, so a copy of the network fights on meanwhile; those few seconds are not
  learned from. `/swordai stop` learns from what is left before it saves, which takes a few seconds.
- **In sparring** the server is your own game, so who hit whom, how hard and with what kind of hit is
  known exactly, as in training.
- **In a fight** on someone's server there is nobody to ask, so it goes by what your game can see: a
  flinch is a hit, your game's own reading of its swing says whether that was a sprint hit, a crit or a
  sweep, and the health bars give the damage (where the server shows another player's health; many do
  not, and then damage dealt counts as nothing). On a slow connection all of this arrives late. It is
  good enough to learn from and less exact than training. What the AI itself is shown of its hits does
  not wait for that: a swing its own game sent while on the opponent counts as landed (0.13.3).
- **Only from fights it can make sense of.** If the critic explained less than a fifth of what happened
  in a batch (`ev` under 0.2 in the report), only the critic learns from that batch.
- **The way back.** The first time it learns from people after other training, everything learned so far
  is copied to `swordai/before-people`. `/swordai undo` puts that back.
- **In the report** these updates are rows with an `s` (sparring) or an `f` (fight) in front of the `spd`
  column, with the same numbers as every other row, and the header says how many steps came from each.

### The strafe plan

So that it fights with its feet as well, it is handed a plan for its strafe keys: which side to go round
the opponent on. This is **AD strafing**: one of A and D is held, and kept held, through the swing, the
recharge and the next swings too, for 3 to 8 swings on end. It goes round the opponent while its combo
lands and has to keep turning to keep it in front; the opponent has to keep turning to hit back at all.
Then comes another circle, to either side, now and then with one swing in between made straight in.

Up to 0.11.3 four plans in ten were something else, which I had wrongly taken for A/D tapping: out to a
side right after the swing and back the other way before the next. That is gone.

Since 18 October about a quarter of the swings are a **straight run** instead: two to five swings with
no strafe at all, the W-tap combo, where the distance is kept with W alone (let go of after the hit,
pressed again to come back to the edge of reach as the sword comes back). Strafing then is charged as
going the wrong way round is. The user: the strafe "dont need to be circle", high tier players W-tap,
though the circle combo "does do really well"; so the circle stays the most of it.

From stage 6 on (0.11.4; stage 7 before) it is handed that plan and paid for following it, only within 3.6
blocks of the opponent (5 up to 18 October; farther off it runs straight in, see `StraightIn` below) and not
while it is getting out of a combo. Stage 6, where the combo is learned,
is also passed on it: the plan has to be followed at least half the time (`mix%` in the report; pressing
a strafe key at random comes to a third). If that has not happened after 150,000 steps in the stage, it
moves on without it.

Up to 0.6.0 the plan also held swings back by up to 6 ticks, and paid a third as much for the strafe. Over
150,000 steps a run followed the strafe 31% of the time, which is chance, and held 50% of its swings,
which is what not holding any gives. So the hold is gone (a ready sword is swung) and the strafe is paid
properly. In 0.6.1 following the plan was also what stage 7 was passed on. From 0.7.0 it is a habit that
is taught alongside: stage 7 is about getting out of combos now, and `mix%` in the report shows how far
the strafing has come without anything waiting for it.

Stage 7 also stops scoring the aim while the sword recharges. Until the sword is three quarters back, being
on or off the opponent is worth the same, and a camera that is left alone is paid a little as long as the
opponent stays on screen. So between hits the view rests and the feet do the work, and the crosshair only
has to be back on the opponent in time for the next swing.

While it circles, the still camera is not what is paid for: going round someone cannot be done without
turning.

`fight` and `spar` hand it this plan exactly when its training does: from stage 7 on, including while
stage 7 is still being learned. Before stage 7 there is no plan, as in its training. (`fightMixAssist` in
the config applies the strafe to its actions by rule before it has learned it; it is off by default.)

## Field of view

While the AI plays your character (training, play, fight) the game is held at **FOV 90**, with the sprint
FOV effect off, and put back if it is changed in the options meanwhile. Your own setting returns when it
stops. A network learns how large an opponent looks at each range, so it only plays right on the field of
view it learned with; that is why it must not change half-way.

The picture the AI gets has its own setting, also 90 by default. `/swordai fov` shows both. If a network
was trained while the game was at another field of view (versions before 0.2.5 set it to 70 when training
started), `/swordai fov 70` gives the AI the 70 picture again: it is handed the middle of each frame, which
is exactly the narrower view, while your screen stays at 90. In `/swordai spar` the picture drawn for the
fake player uses the same setting.

`play`, `fight` and `spar` pick their actions exactly as training does. `playTemperature` in the config
makes it commit to its favourite choice more often when set below 1; that looks steadier but is no longer
the network as trained (versions before 0.4.0 used 0.5).

## What the agent sees and does

Other players are drawn with a flat two-colour skin (blue head, green body). Each rendered frame is read
back and reduced to two small masks of where those colours are, plus a few numbers measured on the masks
(centre, edges, size). The frame is taken when the world is drawn and before the hand, the HUD and the
chat are, so none of those can cover the opponent or be mistaken for it. Besides that the agent knows about its own body: height above the floor, velocity,
pitch, attack charge, health, how long ago it last hit and was hit, how many hits it has taken in a row
without landing one (new in 0.7.0: being hit once and being hit for the third time running call for
different things), and the opponent's velocity relative to it while the opponent is on screen.

It is never given the distance, the reach, or whether the opponent is in reach. Range has to be learned
from how large the opponent looks.

The arena it learns in is always at noon in clear weather; a server is not. So while its picture is
taken from the screen, three things are kept out of it: **fog** (in rain the game fogs everything from
arm's length on, and the colour key only stands a little of that), **rain and snow** themselves, and
**whatever the opponent holds** (a shield covers a good part of the figure). You do not see them either
while it plays; all three are back when it stops.

Two things a player has without noticing are built in, both from the agent's own senses only. A frame is
always slightly old, so positions measured on it are shifted by however far the agent has turned its own
camera since. And it remembers which side the opponent was last seen on and how long ago.

Twenty times a second it picks: a turning speed left/right and up/down, W/S, A/D, sprint, jump, attack.
Those go through the normal input path, so vanilla's own crosshair pick, 3-block reach and attack cooldown
apply.

The camera is moved the way a hand moves a mouse. A chosen turning speed is taken up over a few ticks, so
a turn speeds up and slows down instead of jumping; a camera at rest stays at rest until a real turn is
asked for; and on screen each tick's turn is spread over the frames in between, so the view glides.
Aiming is scored on the whole opponent, not its centre or its head: once the crosshair is on, the best
thing to do is to leave it exactly where it is.

## Curriculum

One network is carried through ten stages; each stage adds controls and nothing is thrown away.
About one episode in eight replays an earlier stage.

1. **Horizontal aim** - turn onto a standing target and come to rest on it.
2. **Horizontal tracking** - find a target anywhere around and follow it.
3. **Vertical aim** - add pitch, keep the crosshair on the opponent.
4. **Footwork** - move with W/A/S/D and sprint while staying on target and in range.
5. **Hit timing** - swing when a hit is on, on a full cooldown, without spamming.
6. **Sprint-hit + W-tap** - hit while sprinting, then release W and press S or strafe to stay at the edge of reach.
7. **Combo escape** - getting out of the opponent's combo: from its second hit in a row, hold S until clear of its reach, then come back with a charged hit. The opponent's follow-up hits cannot be traded with here, only got away from. For the first part of the stage it is pushed to hold S then. Passed on what it does unaided.
8. **Crits** - critical hits, and which hit is the one to make. Jumping is unlocked here. Two drills come first, each until it is passed: the crit in its face (jump and swing on the way down at an opponent that keeps pushing into it, never swings and is hardly moved by a hit), then the punish crit (a critical hit made from the opponent's own knockback, without a jump). Then real fights against the opponent a sprint hit knocks away and, in four fights of five, against the ones it does not: one that cannot be kept off, one that keeps running out, one that goes round it making critical hits, one that stands in a corner, one that takes no knockback, the odd ones (see 14. under *What 0.14.0 found*), and the three of 15.: one that combos and comes after it with a running jump and a critical hit, one that answers each of its hits with a critical hit, and one that backs out of combos with S. Passed once one hit in twelve over those fights is critical and it still takes its punish crits. See *What 0.14.0 found* below for what it does with the jump after that.
9. **Duel** - real fights with knockouts against a saved copy of itself as it was when the duel began (no scripted bot is a match for it by then). Over once it beats that copy in 80% of at least 20 fights on equal terms (0.14.0: one fight in five is set up on equal terms and counted; the rest have the copy on a slow connection, knocked back less, or the AI's view of it stuttering, and are not). Three fights in ten are still against the scripted opponents of the stages before.
10. **Self-play** - the same fight, but now the copy keeps up: whenever it clearly beats the copy (65% of 20 or more fights on equal terms, and half of those against the copies before it), it becomes the copy the next one has to beat. This stage has no end. Like the duel it moves on in `/swordai train headless` only; on the screen the fights are fought and not counted.

A swing that hits nothing costs nothing beyond the sword's charge, which it resets: charging for misses
made it hold back when it was close enough to hit. What is charged is clicking again before the sword is
back to 60% charge, hit or miss, and stage 5 is only passed with few such clicks.

**What 0.14.0 found, and what it does.** What was wrong with it: it does not learn critical hits or
when to make one instead of a combo; its aim breaks and it spins when the opponent is far off;
against a player with 200 ms it panics and hits weakly; against one whose knockback comes 0.3 to 0.5
seconds late it is lost; comboed, it does not swing back, some rounds not once, and does not get
out; it sweeps; in a fight it hits weakly all the time; and what it does in sparring it cannot do in
a fight.

1. *A fake player could not make a critical hit at all.* The game makes one of a hit from a player that
   has fallen some way (`fallDistance` above zero). Carpet's fake player hands its fall over to the
   game in a way that, in 1.21.11, never counts it: measured while falling, it was 0.0 on every tick.
   So in training without the screen and in sparring, where the AI is a fake player, no swing was ever
   critical whatever it did, and the scripted opponents that "jump in for a critical hit" never
   landed one either. A run had spent an hour and 1.03 million steps in crit drill 1 that way. The mod
   now counts a fake player's fall itself (`FakeFall`): 59 of 60 swings made in the air on the way
   down were critical afterwards. On the screen, where the AI is your own character, this was never
   wrong, which is why the drills could be passed there and not without it.

2. *A swing before the sword is back is not made.* Up to now such a swing cost points, and the cost
   was meant to teach it out. It did in training (5% of swings) and not against people (60 to 74% in
   one report). Now it is a rule and not a lesson: until the sword is 95% back (12 ticks after the
   last swing) the attack key is not there to be pressed, in training and in every fight. Weak swings
   since: none. `swingMinCharge` in `config.json`; 0 turns the rule off.

   On someone's server it waits one tick more, until the sword is all the way back (13 ticks;
   `swingMinChargeOnline`). A server counts the time between two swings in its own ticks, from when
   they reach it. If two arrive a tick or two closer together than they were made, or the server is
   running slow, a swing made the moment the sword was back on your screen is an early one as the
   server counts it: no knockback, no critical hit, less damage. With a tick in hand it takes more
   than two ticks of that to make a weak hit of a good one.

3. *A hit that neither knocks back nor is critical earns nothing* from stage 7 on, and is no part of a
   combo. It still counts as a hit in the numbers. Before, it earned what any hit earns.

4. *It had never seen an opponent far away.* Every training fight began 2.5 to 8 blocks apart in an
   arena 12 blocks from the middle to the wall, so anything farther was a picture it had never been
   shown: a figure a few pixels high. From stage 2 on, one fight in four or five now begins 10 to 26
   blocks apart, and the arena is at least 20 blocks from the middle to the wall. In the first
   updates with such starts it was 9.6 degrees off on average; a few hundred updates later 1.1, with
   the opponent in view all the time.

5. *The opponent's connection, and the slow one.* On a server a hit moves the opponent only once it has
   gone to the opponent's own game and that game's answer has come back. Until then the server has it
   walking on as it was: in reach, and able to swing. Against a player a third of a second away that
   is six ticks or more in which you have hit and they have not moved, and to them you are standing
   where you were a moment ago. Training had this small and in pieces (what the AI sees none to 4
   ticks late, the knockback none to 2), so it had hardly met an opponent that stays in its face
   while its sword recharges.

   Now every fight draws one connection and uses it for all of it: how late the knockback moves the
   opponent, and how old what each sees of the other is (half of that, and none or one tick of the
   AI's own). In stage 6 these are ordinary ones: the knockback none to 4 ticks late, more often
   none. From stage 7 on **every other fight is against an opponent on a slow connection**: knocked
   back 5 to 10 ticks late, a quarter to half a second. It is not told which it has in front of it.
   It finds out the way a player does, from whether the opponent goes when it is hit.

   The log line and `/swordai status` say what its fights against each kind came to (`slow
   connection`, `ordinary connection`), so whether it holds its own against the slow one can be read
   off and not guessed at.

   What the first runs with it showed. Against the scripted opponents of stage 8 it landed three
   hits for every four it took on the slow connection when they began, and about one for one twenty
   minutes later (1.1 to 1.3 for one on the ordinary one). Against a copy of itself it is another
   matter: where the copy's knockback came a quarter to half a second late, it landed 38 hits a
   minute and took 57, and after ten minutes of the duel that had not moved. The reason is in the
   game. Whoever's knockback is late stays in reach after being hit and swings back, while the other
   is thrown clear by every hit it takes; between equals that is worth about three hits in two.
   What can be done about it is to make the hits that are traded count for more (the punish crit),
   to answer in the ticks the opponent slips, and not to run back into the next hit, and those take
   longer than these runs to learn. So:

   - **The duel and self-play count only the fights on equal terms**, and one fight in five
     against the copy is set up that way: nothing late for either, the same knockback, no stutter
     in what the AI sees, and the two start facing each other. In all the others something is
     against the AI and nothing against the copy (its knockback late, or six to ten tenths as far;
     the AI's picture of it coming in fits and starts; the copy starting with its eyes on the AI).
     Those are fought and learned from, but who wins them says what a slow connection is worth, not
     which of the two is the better fighter. Counted with them, the duel could not be won, and in
     self-play a fighter put against a copy of itself made that minute lost 18 of 24: at one win
     in ten self-play throws the fighter away and starts again from the copy. The duel is judged
     over the last 60,000 steps (30,000 before, which often held fewer than the twenty counted
     fights it takes).
   - Leave it training (`/swordai train headless`) and watch the `slow connection` figures: that is
     the number that says how it is doing against such a player.

6. *Fights against people no longer teach it unless asked.* `/swordai fight` and `/swordai spar`
   learned by default. In the installation this was found in, the updates from fights against people
   had a critic that explained less than nothing of them and moved the network six to nine times as
   far as an update is meant to (KL 0.085 to 0.137, where training aims at 0.015 and breaks an update
   off at 0.04), and weak swings went from 5% to over 60%. `/swordai learn on`
   is still there; `/swordai undo` still puts back what there was before.

7. *When a critical hit is the hit to make.* The lesson up to 0.13.3 was: jump at the opponent in your
   face. With critical hits possible at last, two versions of the stage were run without the screen,
   about a million steps each, and both came to the same thing.

   - Against the scripted opponent, which a sprint hit knocks away every time, **a jump is the wrong
     call**. While it still jumped (about fifty times a minute) it landed 45 hits a minute and took 43,
     four in ten of them critical. Fifty updates later it had stopped jumping and landed 70 and took
     24. A critical hit does half as much again as a hit, and knocks nobody back; a sprint hit keeps
     that opponent from ever swinging. No reward that pays hits and charges for hits taken makes the
     first of these the better fight, and it should not.
   - So a second kind of opponent was added, in four of ten fights of the stage: **one that cannot be
     kept off**. It takes a fifth to a half of a hit's knockback, runs straight back in and swings as
     soon as it can. That is what a player on a slow connection is for the first ticks after every
     hit, and what one who knows how to take knockback is all the time. Against it the hits are traded,
     and what decides is what each hit does.
   - What the AI found there was not the jump but the **punish crit, hit for hit**: knocked up by the
     opponent's swing, it keeps its own for the way down. Its share of critical hits against that
     opponent went from 15% to between 27% and 38% in forty updates, without jumping, while against
     the one it can knock away it stayed at one hit in twenty and combos. That is the switch between
     the two that was asked for, made by what the opponent does.
   - The jump itself did not last. Drill 1 (the crit in its face) was passed in seventy seconds, with
     57% of its hits critical and 72% of its jumps ending in one, and in the fights the jump was gone
     again after fifty updates, in the drill too, although there it pays half as much again. One
     network makes the call for every opponent, and the opponent that punishes a jump outvotes the two
     that reward it. The drill stays among the fights so that the jump remains something it can do,
     but the stage no longer waits for it: it is passed on critical hits in the fights (one in twelve)
     and on the punish crit still being taken.

   Whether a jump pays against people is not something these runs can say. What they say is that it
   does not pay against an opponent that flies off every time it is hit, and that most of the earlier
   versions of this stage (see below) were attempts to make it pay there.

8. *Comboed, it did not swing back.* Knocked up by a hit it is in the air until the next one, and in
   the air the swing is kept for the way down, where it is a critical hit. Without the screen a
   swing in the air could not be a critical hit (1. above), so all the air ever taught about
   swinging was what a miss there costs. Against a person, who stays at the edge of a sword's
   reach, it then took hit after hit with its sword back and never used it.

   - Swinging back where a hit is there to be made is **a rule now** (12. below), not something it
     may or may not have learned.
   - The scripted opponents **slip, as a person does**: one swing in three comes one to three ticks
     late, with the AI in reach and under the crosshair the whole time. Those are the ticks in which
     swinging back lands first and ends the combo. Before, a follow-up came on the first tick it
     could, every time.
   - What was tried first and taken out again: paying it for any charged swing after a hit, landed
     or not, from 3.6 blocks, and charging nothing for a miss in the air within that distance.
     Against the scripted opponents that looked well (seven or eight hits in ten answered, three
     swings in four landing). Against a copy of itself, which keeps its distance, it threw its sword
     away in the air after every hit it took: thirty swings a minute there, nineteen in twenty at
     nothing, and half the hits it had been landing. A miss in the air costs what it did before.

9. *Sweeps.* A sweep is a hit made on the ground with the sword back but without the sprint behind it:
   full damage, no knockback. It costs 0.4 in stage 8 (0.2) and 0.3 from the duel on (0.2). It was
   cheap while the AI was jumping for critical hits, since one that comes a tick late is a sweep;
   it hardly jumps in a fight now, and what is left of them is a W-tap left out.

10. *What it can do without the screen it cannot at once do on it.* `/swordai fight` plays your own
    character from the picture on your screen. Training without the screen, and sparring, play a
    fake player whose body is run by the rules of a game client and whose picture is drawn for it
    (98 to 99% the same picture, as measured). After three million steps without the screen the
    network had fitted itself to that body. Put on the screen, the same network in the same duel
    landed 0.2 to 0.5 hits for each it took, where without the screen it landed 0.75 to 1.3. The
    crit drill it had been passing in seventy seconds, with more than half its hits critical, began
    on the screen at 17%, with three jumps in ten ending in a critical hit in place of two in three.
    That is "it combos in sparring and cannot in a fight".

    It comes back quickly: ten minutes of the stage's fights on the screen (at `/swordai speed 4`)
    and it was landing 66 hits a minute and taking 20 against the opponent it can knock away, as
    without the screen, with 3 to 5% of its hits sweeps and none early. So:

    - **After training without the screen, train on the screen before a fight.** `/swordai speed 4`
      (or as fast as the computer keeps up with), `/swordai train`, a quarter of an hour, with the
      game's window left open. The last stretch of the run this version was tuned on was done that
      way.
    - The numbers to go by are the same ones (`/swordai status`, the log line), read while it trains
      on the screen.
    - **Against a copy of itself the screen costs it two ticks, and the copy is now given the
      same.** The copy is a fake player on the server. Your character sees it as any player sees
      another, a tick late, and its clicks reach the server a tick late; the copy had neither
      delay. After two hours of the duel on the screen it landed 0.7 to 0.9 hits for each it took
      and won one fight in five on equal terms; the same network, put back without the screen, was
      level with the copy from the first update and passed the duel in 33 minutes (535,000 steps):
      58, 62, 74, 78 and then 80 fights in 100 won on equal terms, with what it does against the
      scripted opponents unchanged (about 80 hits a minute landed and 34 taken). In stage 10, on
      the screen, against the copy that had just won the duel: 33 hits a minute landed and 55
      taken, out of a combo by the second hit one time in three.

      So on the screen the copy now sees the fighter two ticks later than it would
      (`screenCopyLagTicks` in `config.json`). With that the same fights came to 50 hits a minute
      landed and 44 taken, out by the second hit in about half the combos, and against the
      scripted opponents 83 landed and 25 taken: the fighter it is without the screen. Two ticks
      is what made the two level, not something measured on its own.
    - **The duel and self-play are still judged without the screen only** (`/swordai train
      headless`): see 13. below. On the screen the fights against the copy are fought and learned
      from, and the screen is for the stretch before a fight.

11. *Out of a combo by its second hit.* The mark it is held to: when the opponent has landed two hits in
    a row on it, there is no third. It is counted directly (`combos on it N of which a third hit M`
    in the log line; `out by the second hit` in `/swordai status`), for every kind of fight and for
    the combo drills, in which a copy of itself starts with a combo already going on it. Those drills
    now begin one or two hits in, so that a third hit is always its own doing.

    Measured on the screen against that copy before anything was changed: out by the second hit in
    35% of 83 combos, S held on none of the ticks that decide it, and of its swings in the air nine in
    ten at nothing. What stage 7 teaches was gone by then. Of stage 8's opponents one does not combo
    and one cannot be backed away from, and S was paid less than half of what stage 7 pays.

    - Stage 8 pays for backing out what stage 7 does, and the third hit of a combo costs 1.5 once
      more, in stages 7 and 8 (0.6 from the duel on).
    - **Backing out is a rule from stage 8 on**, in training and in every fight: in the twelve ticks
      after each hit of a combo it is taking, from the second hit in a row, S is held for it. Then
      W and S are its own again, and it comes back in for the first hit. `backRule` in
      `config.json`; false leaves it to the push that stage 7 teaches it with.
      (Since 15. this is what it does only where the hit finds it in the air. On the ground it
      jumps with the hit instead: against a player who follows with a running jump, backing out
      was the way into the next hit.)

      How it came to be a rule. With the push held it was out by the second hit in two combos of
      three and the drill paid 0.085 a step. As the push was let down, as stage 7 does, it was out
      of one in two and then one in three, the drill paid 0.04, and within a few thousand steps S
      was held on none of those ticks again. The move works and the network does not keep it: one
      escape model answers every opponent, and against the one that runs straight back in, backing
      off gains nothing. So the push was kept on, and at the strength it settled at it had S held
      on 94 to 98 of those ticks in 100. That is a rule in all but name, and one the network could
      still wear down, so it is said outright. (The figure the push was steered by, and the one
      `/swordai status` showed, counted every tick of a combo with the opponent within three
      blocks, most of them after those twelve; it read 0 to 20% while S was held on 97%. The status
      line and the log now count the twelve ticks themselves: `S held a/b`.)
    - What that comes to on the screen against a copy of itself: out by the second hit in about
      half the combos (47 to 60 in 100 over two hours of the duel; 35 before), and in 5 to 7 of 10
      in the drills. Against the scripted opponents, without the screen, in 6 to 8 of 10. Half the
      time against an equal is about what backing out can give: it puts the two back where they
      began, and from there the next hit is the copy's as often as its own. More than that has to
      come from winning that exchange, which is what the duel and self-play train.

12. *It answers a hit, always, where a hit is there to be made.* A rule, like the one about the
    sword's charge: hit, and not yet having hit back, it swings the moment its sword is back and the
    game has the opponent under its crosshair within reach. The game's own pick, which is what the
    attack indicator shows a player and what a click is decided by, so a swing made by this rule is
    one that reaches. The pick is taken when the step is decided and again when the click is made,
    after the camera has turned; if the opponent is no longer under the crosshair by then, nothing
    is swung and the sword stays back. `answerRule` in `config.json`; false leaves it to the network.

    Made on the tick it learns of the opponent's hit, before the knockback has carried it off, that
    swing is what players call a **hit-select**: a sprint hit takes four tenths off the attacker's
    own speed, so it is thrown less far than the opponent it has just hit and the next hit is its
    own (Minecraft Wiki, *Tutorial:PvP (Java Edition)*). Against an opponent that keeps a perfect
    three blocks there is often no such tick, and the way out is the other one: back out (11.) and
    come in again for the first hit. Jumping as the hit lands, which the same page lists, was not
    used until the first evening against people showed what backing out is worth; see 15.

    (The first version of this rule went by how tall the opponent stood in the picture. Hit, a
    fighter is flying backwards and the picture is a tick old: 98 in 100 of those swings in the air
    met nothing, each of them cost the sword for the next exchange, and in the eight minutes it was
    tried it landed half the hits it had been landing.)

    Counted in the log line: `combos over N of which without a swing back M (K of them three hits
    or more)`, and `swings made by the answer rule`. M is not the number to worry about: two hits
    and then out of it with no swing is a combo it backed out of, as it is meant to. K is the one
    that is meant to be nought: three hits or more and never a swing. On the screen against a copy
    of itself, with both rules on, K was 4 of 302 combos and M 24: in those four the opponent was
    never under its crosshair within reach while its sword was back. Every combo gets the one
    answer or the other: the swing where one reaches, and backing out (11.) from the second hit.

13. *Against a copy of itself, whoever moved first on a tick won.* Stage 10 was reached (5.37
    million steps), and there the fighter lost to a copy of itself made that minute: 10 fights won
    and 25 lost on equal terms. The cause was the order of a tick. The copy made its move, then the
    fighter. Two copies of one fighter come into reach on the same tick and, with the rule about
    answering (12.), trade hit for hit on the same tick from then on; they land as many hits as
    they take, and when both are a hit from the end the hit dealt first is the one that counts.
    Tried both ways for ten minutes: with the fighter first on every tick it won 16 fights of 23,
    with the copy first 2 of 14, landing 292 hits to 278 and 218 to 219.

    Now who moves first against a copy is drawn on every tick (`selfPlayTurns` in `config.json`),
    as on a server it is whichever player's click arrives first. Against the same copy the fighter
    then won 22 and lost 26, which is what a fighter and a copy of it half an hour old should come
    to. The duel was passed under the old order, with the copy first: four fights in five won
    against a copy that had the first move on every tick.

    Two things follow for training. **The duel and self-play are judged without the screen only.**
    On the screen your character is a tick or two behind a copy that plays on the server itself,
    and counted, those fights would never let it take the copy's place; at one win in ten self-play
    would throw away what the screen had taught it and start again from the copy. On the screen
    they are fought and learned from, and `/swordai status` says so. And `/swordai status` shows
    how its fights on equal terms have gone since training was started.

14. *What it would not learn is done for it, and it meets the players it lost to.* From real fights
    on a server after stage 10: it is good, but (a) when the opponent keeps running out it only
    sprints after them; (b) against a player who goes round it making critical hits it hardly ever
    makes a punish crit, and when it does, not reliably; (c) from a little beyond its reach it never
    jumps in for a critical hit; (d) its aim still shakes at an opponent twenty blocks away; and
    it has no answer to a player who stands in a corner and makes critical hits, or to one who
    takes next to no knockback.

    **Why the jump never came.** Five runs taught it the jump in a drill and lost it again in the
    fights, each within fifty updates. A jump that comes to nothing costs, a jump at random makes a
    fighter worse, and against every scripted opponent the sprint-hit combo it already had was
    simply the better fight (against the one that runs: 36 hits a minute landed and 11 taken
    without a jump, 24 and 10 while it was still jumping). So the first thing it learns about
    jumping in a fight is not to.

    **Why the punish crit was not reliable.** Counted per fall: of the falls after a hit in which
    the opponent stayed within a sword's reach, a critical hit was made in 19%. In 49% the
    crosshair was never on the opponent on the way down (the hit moves the fighter, the opponent
    has moved on or was never in the picture), in 21% the sword had already been spent on a plain
    hit, in 10% it was not back yet.

    So these are now **rules**, like the ones about the sword's charge (5.), backing out (11.) and
    answering a hit (12.): done for it on the ticks they apply to, in training and in every fight,
    with the keys they take taken from the network for the tick so that what it learns it learns
    around them. Each has a switch in `config.json`, and `/swordai status` and the report list
    the ones that are on. All of them go by what the network itself is shown, the fighter's own
    body and how large and where the opponent is on screen; how far away the opponent is, is read
    off its height on screen.

    - **The camera after a hit** (`aimRule`). For sixteen ticks after it is hit, until it has hit
      back, the camera is brought onto the opponent: to the middle of it as fast as the hand gets
      there without going past, and with nothing on screen round to the side it was last seen on
      or, never having seen it, away from the way the hit threw it, which is where the hit came
      from. The same in the air of a jump that is made for it (below).
    - **The swing kept for the way down** (`punishRule`). In the air the swing it owes (12.) is
      not made on the way up, where it is a plain hit that uses the sword up: it is kept, the
      sprint is let go of on the first tick of the fall (the game makes no critical hit of a
      sprinting player's swing), and the swing made on the tick after is a critical hit, the
      punish crit. With its feet on the ground it depends on the opponent. Against one that was
      in reach the last time it came down from a hit, the swing is kept on the tick of the hit, so
      that it is there for the fall. Against any other it is made at once, as a trade. (Up to
      14 October it was kept on the ground for as long as the hit was fresh, a second, against
      such an opponent: "you're close but didn't swing", said the user, and so it was. With the
      opponent under the crosshair and the sword back it made no swing on 69 such ticks in 100,
      1395 of 1595 of them kept by this; kept on the tick of the hit only, 52 in 100, and it
      landed 75 hits a minute for 32 taken where it had been 71 for 38. `punishWaitOnGround`
      brings the old way back.) **Keeping the swing is for the first hit
      only:** hit a second time in a row without having hit back, it is being comboed, and the
      swing is made the moment the opponent is under its crosshair, on the ground or on the way
      up, critical or not. No combo is to go by without a swing back (12.), and a swing kept for a
      fall on which the opponent is then out of reach is a combo sat through: with the swing kept
      regardless, one combo in seven went by like that. The exception is an opponent a punish
      crit has been made on: if its last fall with the sword back ended in one, the next is made
      on the way down too, second hit or not, and the first such fall that comes to nothing ends
      the exception. (Made from the ground a moment before the
      next hit of an opponent that stays on top of it, the swing is a sweep, and the sword is on
      its way back for the whole of the next fall: an hour into the first version, eight of its
      hits in ten on the opponent that takes no knockback were sweeps.)
    - **The jump in** (`chaseRule`). At a run towards an opponent a step or two beyond its reach
      that is not itself coming in (backing off, running, or holding its ground), it jumps from
      where the way down will bring that opponent into reach, keeps W and the sprint down on the
      way up, and on the way down lets go of both for a tick and swings on the next: a critical
      hit, half as much damage again as the sprint hit the run would have come to. After an
      opponent that runs with its back turned, as fast as the fighter itself, it makes one running
      jump after the other from any distance up to fourteen blocks, which is the only way it gets
      nearer: jumps taken in a row are carried a quarter faster than a run. (The first version
      took a single running jump to gain a block on a run. It gains a quarter of one: one critical
      hit from 29 jumps.) **It is careful with it:** hit in the air of such a jump, it does not
      make another for thirty seconds, and hit out of the air a second time, not for five minutes,
      from one round to the next against the same opponent: that one hits a fighter out of the
      air. (Against a copy of itself, which swings at whatever comes into its reach, more than
      half of these jumps were hit out of the air and one in four ended in a critical hit; against
      the scripted opponents seven in ten ended in one.) And it does not jump at an opponent that
      is coming in, where the sprint hit is the hit to make. `chaseClosingMost` in `config.json`
      narrows it: 0.15 jumps only at an opponent that is backing off or running.
    - **The crit in its face** (`faceRule`). If its last two hits did not send the opponent away
      (still on top of it as the sword came back), and its next hit lands and the opponent is still
      in its face three to five ticks later, it jumps with W and the sprint up and swings on the
      way down, and goes on like that: critical hits without knockback, one after the other, on an
      opponent that takes none anyway. Two hits, not one: a knockback that comes half a second late
      has the opponent still standing there as the sword comes back, once. And only at an opponent
      that has not hit it for two seconds: one that is hitting it gets the punish crit, which needs
      no jump. Hit in the air of such a jump, it leaves it alone for thirty seconds, and after a
      second time for five minutes.
    - **The uppercut** (`uppercutChance`, four in ten). After that share of the hits of a combo
      of its own, from the second on: a running jump at the opponent as the sword comes back, and
      the sprint hit made while it is still going up. The jump's first tick carries it in farther
      than a run, so the hit reaches an opponent that is a step out of reach by then, and it comes
      off the rhythm the hits before it came on. Hit out of the air on the way, it leaves the
      uppercut alone for a while, as with the jump in. See the PvP tutorial of the Minecraft Wiki for
      what it is for.
    - **A steady hand far away** (`farRestRule`). With the opponent on screen and more than about
      ten blocks off, the camera is steered for it: at rest until the crosshair is two and a half
      degrees off the opponent, then onto it in one move and at rest again. The hand model starts a
      turn at a degree a tick and no less, and at twenty blocks the whole opponent is a degree and
      a half wide; the network went on correcting there, each correction as large as the target.

    **And stage 8 has the opponents to use them on**, each a share of its fights (and so of the
    scripted fights that the duel and self-play keep going back to):

    - the **runner**, of which half now keep away for good: back turned and at a sprint whenever
      the fighter is within seven blocks, until it is hit, then two to five seconds of fighting and
      off again;
    - the one that **goes round it** making critical hits, in half of those fights starting close
      by and out of the picture, off to a side or behind;
    - the one **in the corner**: it stands where the walls meet, turns to face the fighter and
      jumps on the spot for a critical hit whenever the fighter comes into reach;
    - the one that takes **next to no knockback** (none to 15%), stays on top of the fighter and
      makes a critical hit every time its sword is back;
    - the **odd ones**: one that jumps all the time, one that spins on the spot as it walks in,
      one that stands like a statue and then runs at it, one that changes direction every few
      ticks, one that clicks as fast as it can, one that keeps ducking;
    - and every other scripted opponent is **drawn afresh**: how quick it is, how well it aims,
      how near it likes to be, how often it jumps, backs off, is late with a swing or comes in
      for a critical hit.

    The slow connection is slower as well: knockback up to 0.6 seconds late (0.5), what it sees of
    the opponent up to two ticks older, and the stutter half as heavy again.

    **What it came to**, measured on the screen in first person (`/swordai speed 4`): "before" is
    the network as it was installed on 12 October, in stage 8; "now" is the last day of training
    with the rules, stage 8 and then self-play with the stage 8 opponents mixed in, twenty hours in
    all. Hits are per minute of fighting, landed and taken; ranges are from one hour to the next.

    | | before | now |
    |---|---|---|
    | swings made before the sword was back | 0 | 0 |
    | falls after a hit, opponent in reach, that end in a critical hit: against the one that goes round it | 19% (all) | 60 to 69% |
    | ... against the one in the corner | | 67 to 80% |
    | ... against the one that takes no knockback | | 86 to 94% |
    | ... against the odd ones and the other scripted opponents | | 61 to 75% |
    | the opponent never under the crosshair on such a fall | 49% | 8 to 12% |
    | against the runner: hits landed and taken | 36 and 11 | 46 to 56 and 13 to 17 |
    | jumps in at an opponent beyond its reach, per hour of fighting | hardly any | 90 to 115 |
    | ... that end in a critical hit | none | one in three (seven to nine in ten where the opponent is not running) |
    | ... hit out of the air, against a copy of itself, per minute | | 0.3 (1.8 before it remembered) |
    | uppercuts landed, per hour of fighting | none | 27 to 36; six in ten of the jumps made for one |
    | far away (11 blocks or more, crosshair on the opponent): ticks the camera is still | 15 to 23% | 39 to 56% |
    | ... times it changes the way it is turning, per 100 ticks | 13 to 16 | 1 to 2 |
    | sweeps, of its hits | 5.6% | 3.8 to 5.4% |
    | combos on it that end at the second hit | 59% | 62 to 76% |
    | combos that go three hits or more without one swing back | 4.1% | 4.5 to 7.1% |

    The last line is the one it is held to hardest, and it is not at nothing. Against the scripted
    opponents it is: none to one combo in a hundred. Against a copy of itself it is one in
    fifteen, and one in seven where the fight begins with the copy already two hits into a combo.
    Those are combos in which the copy was never under its crosshair within a sword's reach: on
    the screen it sees its opponent a tick or two late, an opponent that comes in at a run is in
    its own reach before it is in the fighter's, and the hit throws the fighter clear. What it
    does about those is back out (11.), which is the other three combos in four.

    Of the jumps in, about half never bring the opponent into reach: those are the jumps on the
    way, after one that runs. What is left of the falls that do not end in a critical hit: the
    sword was not back yet (one in ten; it had just swung), the opponent was behind it and the
    turn took longer than the fall (one in ten), or the swing was made at once as a trade.
    Against a copy of itself that last one is the rule and not the exception: seven falls in ten
    there are answered at once.

    Against each kind, hits landed and taken a minute and the share of its hits that were critical:
    a copy of itself 47 to 51 and 47 to 52 (8%); the runner 46 to 56 and 13 to 17 (20%); the one
    that goes round it 65 to 69 and 59 to 68 (41 to 51%); the one in the corner 68 to 77 and 46
    to 62 (40 to 56%); the one that takes no knockback 89 to 94 and 97 to 99 (92 to 96%); the odd
    ones 69 to 74 and 27 to 44 (20 to 37%); the other scripted opponents 72 to 76 and 37 to 45
    (24 to 32%). **The one that takes no knockback it does not beat:** nothing moves that one, its
    critical hits cannot be stepped out of, and what is left is hitting back as hard, which it
    does, fall for fall. The one that goes round it is about even in health for the same reason.

    **Something it learned from the rules that it should not have.** With the punish crit all but a
    sure thing, being hit by an opponent that makes critical hits had become worth having: in the
    crit stage a punish crit earned 4.5 and a critical hit taken cost 2.3. After eighteen hours it
    had found that out against the opponent in the corner, and went and stood in its reach: the
    hits it took from that one went from 42 to 70 a minute within the hour, for as many landed.
    In health that is a trade at best. A critical hit taken now costs what a punish crit earns
    (`critTaken`, 2.5 in the crit stage and 0.3 in the duel and self-play), so that the exchange
    comes to nothing and not being hit is what pays; the network that is installed is the one from
    before that habit, trained on for three quarters of an hour with the cost in place (against
    the one in the corner: 68 hits landed and 60 taken a minute over that time, so that habit is
    not all gone).

    **Not tried against a person.** All of it is from training fights, on the screen, against
    scripted opponents and copies of itself.

15. *Backing out of a combo was how to beat it.* The first evening against people (13 October, 41
    fights of half a minute against 24 players, read from the game's log): 1016 hits landed and 846
    taken, 20 knockouts and none taken. Against seven of those players it took more than it landed,
    at the worst 13 to 29, and the user, watching, said how those players did it:

    - **The loop.** Its opponent lands two hits in a row. The rule in 11. holds S for it. That opens
      the three blocks into four or five, which is what a running jump needs. The opponent jumps
      into that room and makes a critical hit on the way down; the critical hit throws the fighter
      back, the rule holds S again, and so on round. "It is still being comboed in some form." A
      running jump covers ground faster than S does, and the one who comes in sees the one who
      backs off where it was a moment ago, which is nearer.
    - **The answer to its own hit.** On a server that hands out part of the knockback (or against a
      player who jumps with the hit), the opponent it has just hit comes down within a sword's reach,
      and makes a critical hit on the way down while the fighter's sword is still on its way back.

    **First the opponent, then the cure.** Two scripted opponents were made for these, and before
    anything was trained each was put against the installed network with its learning switched off
    (`actorLr` 0), on the screen, to see that it wins the way the players did:

    - *The jump critter* (`jumpIn`; `jump critters` in the log). As good a player as a script can be:
      no reaction time, half a degree to a degree and a half of aim, its sword always all the way
      back, sprint hits on rhythm from the edge of its reach. Whenever the fighter is going away
      from it beyond that reach (walking backwards, or still carried by the last hit), it takes a
      running jump from where the way down will bring the fighter into reach, sprints to the top of
      it, lets go and makes a critical hit on the way down. Each fight begins one or two hits into
      its combo, with the fighter held until those have landed (the head start the combo drills
      have). The first version did not combo and had no head start, and the user, watching the
      window, said so: "your trainer is too weak".
    - *The punisher* (`punish`; `punishers`). The stage's opponent, taking three to eight tenths of
      the knockback, which keeps its swing for its own way down from each hit it takes.
    - *The backer* (`backers`): hit, it walks backwards for twelve ticks with its sword ready, which
      is what this fighter did. It is there for the fighter to practise on.

    Against the installed network, not learning, with the rules it was installed with:

    | | hits landed / taken a minute | combos on it that reach a third hit |
    |---|---|---|
    | the jump critter | 34 / 77 (37 of them critical) | 57% |
    | the punisher | 77 / 77 (72 critical; 62 of them answers to a hit of its own) | 6% |

    34 to 77 is what the worst of the evening's fights were (13 to 29, 16 to 29, 17 to 27).

    **What it does now**, each a rule with a switch in `config.json`, in training and in every fight:

    - **It jumps with the hit instead of backing off** (`jumpResetChance`, 1.0; `JumpReset`). Hit for
      the second time in a row, on the tick its own game learns of the hit, its feet still on the
      ground, it presses jump with W and the sprint key down: a jump reset. A running jump adds a
      fifth of a block a tick towards the opponent against a knockback of four tenths (seven with a
      sprint behind the hit), so it is thrown half as far, or two thirds, and comes down with the
      opponent in its reach and the swing it owes (12.) there to be made. S is held only where the
      hit finds it in the air (`backRule`; false, and S is not held for it at all: against the jump
      critter that made no difference, 56 hits landed to 67 taken for 57 to 71). **Never on the
      first hit**: a fighter that takes no hit
      at its full knockback cannot be comboed by anyone, and the user asked for it to be used
      sparingly ("it is really not fair"). As it is, one hit in eight that it takes is jumped with,
      and two in seven against the opponent this is for. S in a combo is no longer paid in the crit stage, the duel and self-play
      (`comboBack` 0).
    - **It runs at a player who jumps at it** (`meetJumpRule`; `MeetJump`). A critical hit can only
      be made on the way down. With its sword back (or back within three ticks), an opponent that is
      on its way up and coming is run at, W and the sprint key down, from before it has landed
      itself, and on the ground the swing is made the moment the game has that opponent under the
      crosshair: also where the swing would have been kept for its own way down (14.), and where S
      would have been held.
    - **It steps back from an opponent that answers its hits** (`stepBackRule`; `StepBack`; **off
      since 14 October**, see below). Hit
      three to thirteen ticks after a hit of its own, its sword on the way back, twice within five
      seconds: that opponent is one that does. From then on, for as long as it goes on, the fighter
      is walking backwards from the tick each of its hits lands, until it is four blocks off or its
      sword is four fifths back, and then comes in again; if the hit turns out to have sent the
      opponent flying, or the opponent is running, it goes after it instead. No swing is made early
      for any of this. (Stepping back from every hit that had not sent the opponent away, read off
      the two to five ticks after it, was tried first. It protects as well, and against the opponent
      that keeps running out it cost three hits in ten: 43 landed a minute for 61.)
    - **It does to others what was done to it** (`chaseInCombo`). The chase jump of 14. is now also
      the next hit of its own combo: at an opponent that backs off beyond its reach after being
      hit, as soon as the way down will bring that opponent into reach.
    - **From five blocks off it closes with running jumps** (`hopRule`; `JumpIn.HOP`). Going at
      it with its sword back, an opponent five to fourteen blocks off that is not coming in fast
      itself is jumped at, W and the sprint key down, and again each time it lands while it is
      still that far, which is faster than running. It does not wait to be at a full run: two
      ticks of going at the opponent, at a walk or more, are enough (waiting for the run, it let
      80 such ticks in 100 go by, and jumped on 2). The user, 14 October: it "still never sprint jumps to opponent if
      distance is above 5 block". A rule for it alone, since 14 October: hit out of the air at the end of one,
      it leaves these alone for ten seconds, and for a minute after the third time. Before, they
      went with the chase jump's reckoning, which leaves the jump alone for five minutes, across
      rounds, after two such hits, so that against a player who had hit it out of the air twice it
      never ran in with a jump at all.

    The trainer learns of a hit on the same tick the fighter's own game does (97 hits in 100; the
    log counts it), which is the tick a fight on a server goes by, so a jump made with a hit is
    made in training when it would be made there.

    **What the rules alone change**, the same network, still not learning, against the same two:

    | | hits landed / taken a minute | combos on it that reach a third hit |
    |---|---|---|
    | the jump critter, as installed (S) | 34 / 77 | 57% |
    | ... jumping with the hit instead | 56 / 71 | 12% |
    | ... and stepping back | 56 / 68 | 3% |
    | the punisher, as installed | 77 / 77, 62 of them answers to its hits | 6% |
    | ... with the step back | 59 / 52, 23 of them answers | 0% |

    **Tried and left switched off**, because the user asked for the jump with a hit to be used
    sparingly and these are the opposite, for little or nothing:

    - *The first hit too, against an opponent that follows its hits through the air*
      (`jumpResetChasers`). Every opponent that makes critical hits counts as one of those; it jumped
      with nearly every hit it took (77 a minute against the jump critter, 20 without) and landed
      62 hits to 78, for 54 to 68.
    - *Every hit that finds its sword on the way back from a hit of its own*
      (`jumpResetCounterChance`). In a fast exchange that is nine hits in ten. 69 hits landed to 73
      taken against the jump critter, for 55 to 68; no better against the punisher; and one hit in
      six a sweep, for one in twelve.

    **What it came to.** The installed network with all of the above, not learning, on the screen,
    45 minutes of fighting against every kind of opponent ("before" is 14.'s table, or the two rows
    above for the new opponents):

    | | before | now |
    |---|---|---|
    | the jump critter: hits landed / taken a minute | 34 / 77 | 57 / 68 |
    | ... its combos that reach a third hit | 57% | 8% |
    | the punisher | 77 / 77 | 60 / 50 |
    | ... hits taken with its sword on the way back from a hit of its own, a minute | 62 | 23 |
    | the backer (new) | | 56 / 26; of the jumps it makes at it, half end in a critical hit |
    | the one that keeps running out | 46 to 56 / 13 to 17 | 43 / 10 |
    | the one that goes round it | 65 to 69 / 59 to 68 | 60 / 40 |
    | the one in the corner | 68 to 77 / 46 to 62 | 67 / 45 |
    | the one that takes no knockback | 89 to 94 / 97 to 99 | 93 / 98 |
    | a copy of itself (over the night's training) | 47 to 51 / 47 to 52 | 45 to 47 / 46 to 52 |
    | combos on it that end at the second hit, all opponents | 62 to 76% | 93% |
    | combos that go three hits or more without one swing back | 4.5 to 7.1% | 0.7% (2 of 307) |
    | swings made before the sword was back | 0 | 0 |
    | sweeps, of its hits | 3.8 to 5.4% | 5.6% |
    | hits it jumps with, of the hits it takes | none | 13% (28% against the jump critter) |

    **The jump critter still lands more hits than it takes**, 68 to 57. It is a script with no
    reaction time and no error that starts every fight a hit or two ahead; what has changed is that
    its combos end at the second hit instead of going on round. **The one that takes no knockback
    is where it was.** Sweeps are a little up: a fighter that has stepped back is not at a sprint
    when it comes in again.

    **Five hours of training on the screen added nothing to this.** From the installed network,
    with the rules and the three new opponents, a million steps: the numbers of every kind of
    fight stayed where the rules had put them in the first minutes, and of three networks put side
    by side at the end (the last, the one of half past two, and the one it began from), each for 45
    minutes of fighting with its learning off, the one that had not
    been trained on was as good as any and better than the last (against the punisher 60 / 50 for
    66 / 67; against the opponents of the earlier stages 79 / 29 for 72 / 40, with a third hit in
    one combo in eight for one in three: with S no longer paid it had begun to drop S there too,
    where nothing replaces it). So the network that is installed is the one of 13 October, and what
    is new is the jar. The night's networks are kept in `run\kept-v17` to `kept-v22`.

    **Not built:** turning round and running out of a combo with jumps, which the user named as
    the better way to run if it runs at all. It runs at the opponent now instead.

    **Then it met people again (14 October), and the step back went.** Fifteen fights against six
    players: 357 hits landed and 333 taken, where the evening before had been 1016 and 846: 51 hits
    a minute landed for 47, and 47 taken for 39. The user's word for
    it: "it can hardly combo and loses against weak opponents as well". What a person sees of the
    step back is a fighter that hits and walks away, and what a person does about it is what was
    done about S: come straight on, and have the first hit. Against the scripted opponents it had
    looked like a gain, and put against ordinary ones that jump with a third of the hits they take,
    with the network not learning, the three ways came out alike (64 hits landed and 32 taken a
    minute with everything on, 70 and 42 without the step back, 68 and 36 with yesterday's rules).
    The scripts do not press in on a fighter that backs off the way people do, and that is the part
    of it that counts. `stepBackRule` is now false; the jump with a hit, the run at a jumper and the
    chase jump in a combo stay.

    **The same evening: "close but didn't swing", and no running jump from far off.** The user
    named two more things: it is close and does not swing, and "still never sprint jumps to
    opponent if distance is above 5 block". The first was the swing kept for the way down on the
    ground for a second after each hit (see the punish crit in 14.); it is now kept on the tick of
    the hit only. The second had two causes: the running jump from far off went with the chase
    jump's five-minute reckoning, and it waited for a full run before jumping, where most of the
    time the fighter was still getting up to speed. Both are changed (see *From five blocks off it
    closes with running jumps* above). With the network not learning, against the same opponents,
    with and without these jumps: 74 hits landed a minute for 30 taken and 76 for 31 against the
    ordinary ones, 62 for 65 and 64 for 72 against the one that follows its hits with a running
    jump and a critical hit; out of a combo by the second hit 69 and 70 in 100. Running jumps from
    five blocks off went from one in five minutes to one and a half a minute.

    **18 October: the player who still beat it, and what a swing a tick early is worth.** After
    four days of fights it won nearly all of (101 fights, about 1.3 hits landed for each taken, no
    deaths), the user's friend still came out even with it, by "spacing aka timing the hit so
    precisely": he takes its sprint hit (a server that hands out less knockback leaves him in
    reach), jumps, and makes a critical hit on his way down while its sword is still coming back,
    the punish crit, every time. The scripted opponent made of that (`StageSpec.timerShare`,
    `BotProfile.timer`) holds a step outside its reach, jumps with every hit it takes, answers on
    its way down, and with the fighter's sword on its way back times a jump to come down a tick
    or two before the sword is back. Tried and dropped: meeting a fighter that runs in with a
    jump timed to be on its way down as it arrives (the run at a jumper, `MeetJump`, hit it out of
    the air on nine jumps in ten). With the network not learning, at the rhythm it has on a
    server (thirteen ticks between swings), it went 55 hits landed a minute to 58 taken, 44 of
    them critical: even in hits and behind in damage, as against the friend. The log now has the
    damage dealt and taken for each kind of opponent (`damage ... dealt / ... taken`), which is
    what decides that: a critical hit is worth half as much again.

    What changed with it:

    - **A swing a tick early is the network's to make** (`swingMinCharge` 0.88, `SwingGate.EARLY_CHARGE`):
      eleven ticks after the last swing, which the game counts as 0.92 of the charge (more than
      the nine tenths knockback and a critical hit ask) and nine tenths of the damage. The user's
      words: a hit with the sword 90% back "is considered ok in game". Whether and when to use the
      tick is learned; a swing a rule makes still waits for the sword to be all the way back
      (`ruleSwingCharge`). On someone's server it is a tick later for both, for the server's own
      count (`swingMinChargeOnline` 0.96, twelve ticks, was thirteen; `ruleSwingChargeOnline` 1).
    - **No more keeping the swing in a combo for the crit** (`punishInCombo` false). Against an
      opponent it had made a punish crit on, it kept its swing for the way down even while being
      comboed, and made it on the exact tick, time after time. The friend's verdict was that it
      looked like a trigger bot, not a fighter. Being comboed, it now swings back as soon as it can.
    - **It looks for an opponent it has lost** (`lostRule`; `LostRule`): off the screen for three
      ticks, the camera turns to the side the opponent was last seen on, at the hand's full speed,
      and comes level, until it is found, and stays on it for four ticks more. The user: "it
      doesn't know to spin 180 degrees to find my opponent", or "looks at the ground and spins".
    - **Copies of the people it fights** (`watchStyles`; `PersonStyle`, `StyleWatch`). In
      `/swordai fight` it counts how the opponent fights (the distance it keeps, how often it
      answers a hit on its way down, whether it chases a fighter that backs off with a hit from
      the air, how far a hit sends it, whether it jumps with hits, its ping) into
      `swordai/people/<name>.json`, a few hundred bytes a player. Training then fights scripted
      opponents with those habits (`StageSpec.peopleShare`), the players it lost to most often.
      Not a recording: a recording does not answer what the fighter does differently.
    - **Backing off with S** was tried once more, against an opponent that answers its hits and
      has never come after it through the air (`StepBack.chaser`): it came on so rarely against
      the timer that it made no difference, and stays off.
    - **The opponent's sword is an input** (`ObsSpec.SC_OPP_CHARGE`): how far back it is, read off
      the last time the opponent's arm was seen to swing (misses too), the way a player reads it.
      That is what spacing is made of, in the user's words: out of reach while the other's sword
      is ready, back in at three blocks while it is coming back, without having to be exact about
      the three blocks. Its weights start at zero, so a network from before plays as it did until
      training has made use of it. The log's `distance kept` part says how far it stands off with
      the opponent's sword back and with it on its way back; the two drawing apart is the sign of
      it being learned.
    - **The player at the edge of the reach** (`StageSpec.spacerShare`, `BotProfile.spacer`), as the
      user described the player it still loses rounds to, "mix of both": one that stands three blocks
      off and hits whatever comes into its reach, perfectly, and one that comes in with a jump for a
      critical hit and backs straight out with S, then again. A third of these fights each, and a
      third switching swing by swing; the standing kind is logged on its own (`standers`). What it
      is and why it beat the fighter is written up in `REQUIREMENTS.md`.
    - **And the player spacing beats** (`StageSpec.whifferShare`): it walks in and swings the moment
      its sword is back, whether or not it can quite reach. Held off at the edge it misses, and its
      cooldown is the opening: where the fighter learns to space others, not only to get out of it.
    - **The 0.14.0 model as the yardstick** (`oldModelShare`; `swordai/old-model.bin` and the settings
      it played with, `old-model-config.json`). The user: the old model can be spaced, so a network
      that spaces it, finds the timing loophole and combos it has learned to choose its timing. It
      plays as it did on a server (its sword all the way back every time, the rules it had then) in
      three of every ten fights against copies, frozen, and the log counts those fights won and lost
      (`fights against the 0.14.0 model`) and how they went (`0.14.0 model`).
    - **Rounds won** (`rounds ... won ... lost` for each kind of opponent): the opponents in training
      cannot die, so every 20 health dealt or taken closes a round, won by whoever dealt more in it.
    - **Every round of `/swordai fight` is recorded** (`recordRounds`; `RoundRecorder`) to
      `swordai/fights`: a gzipped text file a round (a few kilobytes), with what likely decided it
      on top (beaten to the hit with its sword ready, hit in its own cooldown, crits from jumps in,
      the opponent backing out with S after its hit, combos taken, first hits of each exchange) and
      the timeline below, a line a tick: the distance, both swords, the keys and which rule pressed
      them, how the opponent moved, and every hit. `swordai/fights/rounds.csv` has a line a round.
      The newest 200 are kept. A won or lost round is also told in the chat in one line.
    - **Out of reach it runs straight in** (`straightInRule`; `StraightIn`). The user: it strafed
      while moving in to get close; "just walk straight and only strafe when it is combo". An
      opponent that backed off in the middle of its combo was gone round in a circle to be got at,
      because the strafe plan went on within five blocks. Now with the opponent in view and more
      than 3.6 blocks off (by its height on screen), the strafe keys are let go of; within that,
      and for half a second after a hit taken, they stay the network's. The log counts the ticks
      (`straight runs`).

The run these numbers are from is the one in *Running it with nobody at the keyboard*: each update writes
one line to the log with what the fights of each kind came to.

From stage 7 on the opponent stops being easy: it reacts sooner, aims better, turns faster and sprints
into its hits, so being hit is a regular part of the fight. In stage 7 it also keeps a combo going: for
a good second after a hit of its own has landed, its next swing comes the moment the AI is in its reach
and its sword is back, with no reaction time. **That swing comes on rhythm, and it can miss**: if the AI has
backed out of its reach by then (but is still within a block and a half of it), the bot throws it
anyway, hits nothing, and stands there with its combo over and its sword recharging. That is the opening
backing out is for: the AI comes back in with its own sword charged and lands a hit the bot cannot
answer.

Up to 0.9.1 this bot was wrong, and S could not be learned against it. It only ever swung when the swing
would land, and its follow-up reached 0.4 blocks farther than a sword. So backing out won nothing: the
bot never missed, and on the way back in the AI was hit first, every time. Holding S was worth no more
than not holding it, and no amount of pushing would have made it stick.

The stage 7 bot also backs off now and then when it is hit, instead of walking straight back in, and it
begins some of its attacks by **jumping in for a critical hit** (three in ten). The AI meets critical
hits here, a stage before it learns to make them, and being hit by one costs it more than a plain hit.
In stage 8 the bot does not follow up but backs off after its hits and jumps in for a critical hit on
four swings in ten. After six in ten of those critical hits it does not back off but keeps running at
the AI for half a second, the way a player stays on top of someone they have just hit. That is when
there is something to punish: up to 0.12.3 it backed off after every hit, so by the time the AI came down
from the knockback nobody was in reach, and a punish crit could not be made against it at all.

**How the opponent takes a hit (from stage 6 on).** A real player is knocked back by its own game, which
hears of the hit a moment after the server dealt it. Until then the server has it walking on as it was:
still in reach, still able to swing. And much of the time it is not thrown the full distance: it was
running into the hit, or it jumped as the hit landed. A fake player in a singleplayer world flies off
the instant it is hit, all the way, every time. Combos learned on the second kind do not hold on the
first: in training the AI chained 7.8 hits on the stage 6 bot, and in fights against people 1.3 to 4.
So from stage 6 on, where combos are learned, each fight draws its own: a delay of none to 2 ticks
before a hit's knockback moves the opponent, and in every other fight only 60% to 100% of the
knockback. The report says how many knockbacks were held back. (The delay was added to stage 7 and
later in 0.10.0; stage 6 and the weaker knockback are new in 0.11.3, and a run that is past stage 6
goes back to it: `/swordai stage 7` or `/swordai stage 8` skips that.)

**Opponents that crit, later on.** In the duel and in self-play the opponent is a copy of the AI, and a
copy crits no more than the AI itself does, so it could finish the curriculum having hardly met one. So
there about one fight in five is against the scripted crit-jumper of stage 8, and in those fights it has
the habit the way a person has it: it jumps in for half to nine tenths of its swings, drawn per fight.
`/swordai status` and the report say how many of the hits it took since the last update were critical.

**Lag.** In a singleplayer world nothing is ever late, and a fighter that has only fought there is lost
on a server, where the opponent is shown where it was a moment ago and hits land from where you had
already left. From stage 7 on each training fight therefore draws its own lag. (0.14.0: one connection
per fight, used for what the AI sees, what the opponent sees and how late a hit's knockback moves the
opponent, and every other one a slow one; see *What 0.14.0 found* above. The figures below are what it
was before.)

- What **the AI sees of its opponent** comes none to 4 ticks (200 ms) late. This is the longer way
  round on a server: when the AI lands a hit, the opponent is knocked up only once the hit has gone to
  the server, on to the opponent's own game, and the answer has come all the way back. In a
  singleplayer world it pops up at once. Being knocked up and coming down again is nearly all up and
  down on the screen, which is why aim against a lagging player goes wrong vertically first.
- In **every other fight the opponent also arrives in fits and starts**, as from a connection that
  stutters: now and then nothing new for 2 to 5 ticks, then all of it at once. On the screen that is a
  player who stands still and then jumps on.
- Both hold on the screen and without it. On the screen the game is told of the opponent's moves a few
  ticks late and then does with them exactly what it does on a server (glides to the new place, draws
  it there, judges a click against it there); nothing else in the game is touched. The report says how
  many of the opponent's moves were held back that way. (Up to 0.8.0 this was only done when training
  without the screen, and only as a steady delay of up to 3 ticks.)
- What the **scripted bot and the copies of itself see of the AI** is none to 3 ticks late: they aim
  at, walk up to and swing at the AI where it was, and a swing that connects there lands on the AI
  where it is now.
- `/swordai fight` with `/swordai learn on` remains the only place it meets a real connection. Its
  summary now also says how far off the opponent's head the crosshair was on average, sideways and up
  or down, so a fight where the aim went wrong can be told from one where it did not.

**Getting a move tried.** By stage 7 the AI has spent six stages holding W, and S is a key it all but
never presses; jump is the same in stage 8 a little later. Paying for the move does nothing then: what
is never tried is never paid. With 0.8.0 that is how both stages went. So while a stage that teaches
such a move is young, something is done about the one key in the one situation it is for (S in the
twelve ticks after a hit of a combo; jump three to five ticks after a swing, on the ground, with the
opponent on screen):

A **push** is added to the network's own choice there, as much as it takes for the move to be made on
a set share of those ticks (S on 60%, jump on 50%). If the network leans against it, the push grows. It
is part of the policy that is learned about, so nothing is learned wrongly from it, and it ends where
its situation ends: outside those ticks the network's own choice is untouched.

The push is held for the first part of the stage (30,000 steps in stage 7, 40,000 in stage 8), then let
down to nothing over as many steps again, and the stage can only be passed after that, on what it does
unaided. From there the rewards decide whether it keeps the move. `/swordai status` and the report say
how strong each push is and how often the move was made since the last update.

**0.9.0 to 0.10.0 did more than push, and it did harm.** Each update also pulled the network's own
choice towards the move in that situation, to make the move its own. The network could not tell "in
that situation" from "always" fast enough: it came to hold S all the time and stopped pressing W. That
is removed in 0.10.1. A run that trained on those versions is best put back to before stage 7:
`/swordai stop`, then `/swordai retry 7`, which brings back the network as it was when it passed stage 6
(what it replaces is kept in a `backup-...` folder), then `/swordai train`.

**Two small models of their own (0.11.0).** The jump key and, while the AI is in the opponent's combo,
the W/S key are no longer decided by the main network. Each has a small model of its own inside the same
checkpoint, fed by the AI's own body and by where and how large the opponent is on screen:

- the **crit model** decides the jump, always;
- the **escape model** decides W/S in the twelve ticks after each hit of a combo it is taking (from the
  second hit in a row on): the ticks in which it backs out or does not. The rest of the time W/S is the
  main network's, and with it coming back in at a sprint for the hit.

A model of its own starts undecided, so it tries its key from the first tick, and nothing it learns can
change what the rest of the network does: what is learned about its key goes to it alone, and its answer
is only used for its own key in its own moment. That is what went wrong before, both ways: taught inside
the one network, S in a combo either never got started or spread to everywhere. A checkpoint from before
0.11.0 loads as it was and gets the two models new.

**The crit model has more than the jump (0.12.0).** A critical hit is a swing made on the way down
*with the sprint off*. In 0.11.3 the AI jumped 24 to 55 times a minute in stage 8 and not one of its
hits was critical, because 70 to 96% of them were still sprint hits: it has spent a million steps
learning never to let go of the sprint. So whenever a critical hit can be in the making, the crit
model also has the sprint key and the swing, and W/S unless the escape model has those, and the main
network (the sprint-hit fighter) has no say in them:

- in a jump of its own, from take-off to landing;
- coming down after the opponent's hit has knocked it up, which is where a punish crit is made.

It starts out letting go of W now and then and leaving the sprint key up, which ends the sprint, and it
takes the swing as the main network makes it (at full charge, which is when it is coming down) and can
move it from there.

**Why critical hits stayed at exactly none (found in 0.12.1).** A hit is critical only if the server
does not have the attacker sprinting. Two things kept the server's sprint on at every one of its hits:

- The main network lets go of W after each hit and presses it again with the sprint key down (the W-tap
  it learned in stage 6). That starts the sprint again every time. So long as it held the keys, a
  critical hit was not rare but ruled out, however often it jumped.
- In 0.12.0 the crit model took over W in the air and pressed it now and then. The game starts a sprint
  when W is pressed twice within seven ticks, sprint key or no (its double-tap), so that started the
  sprint again as well.

Now the crit model starts out leaving W alone in the air and keeping the sprint key up: the sprint ends
on the first tick off the ground and stays ended. When stage 8 begins, that part of it starts over with
these values, whatever it had learned before.

**Crit drills come first (stage 8).** Every fight of stage 8 is a crit drill until the drills are passed;
only then do the stage's own fights begin, with three in ten still drills. A drill is 20 seconds. In the
drills that come first the AI **cannot walk, strafe, sprint or turn**: its camera is kept on the opponent
for it, from whatever side the opponent stands on, and all it has is the jump key, and the swing while it
is in the air. With no W there is no sprint, and with no swing on the ground the sword cannot be used up
before the jump, so every swing it makes is one a critical hit can come of. Since 0.13.1 there are two
of them, taken in order (see *When to crit, when not, and the punish crit* below).

The swing in the air is the crit model's own (0.12.3), and it starts out knowing the one thing a critical
hit turns on: swing while coming down with the sword back, and not before. Up to 0.12.2 the swing in the
air was still the main network's, and the main network only knows the air from being knocked into it by
a hit, where it learned to swing back at once. So in its own jumps it swung on the way up with the sword
half back, which is no critical hit and starts the sword over, and then again after it had landed: early
or late, never on the way down.

**When to crit, when not, and the punish crit (0.13.1).** With 0.12.3 the AI had the timing of a critical
hit and nothing else about it. In a real fight it jumped after every swing and swung on the way down
whatever was in front of it; most of the time the opponent was out of reach by then, the swing cut the
air, and the sword was gone for the hit that could have been had a moment later. The reasons were all in
how it was taught:

- The one drill had the opponent standing within reach, every time. How far away the opponent was never
  varied, so it was never something to learn from.
- In the stage's own fights the jump itself was paid (anywhere within four and a half blocks) and pushed
  for (three to five ticks after every swing, as hard as it took). After a sprint hit the opponent is
  flying away at that moment. So the jumps it was paid and pushed to make were exactly the ones that
  cannot end in a critical hit.
- In the air the swing was decided from two facts about its own body, falling and sword back, and nothing
  about the opponent.
- And the punish crit could not happen. The opponent meant for it in the drills stood still and hit with
  a plain swing, with nothing following; the stage's own opponent backed off after every hit; and the AI,
  knocked into the air while sprinting, swung on its first tick coming down, which the game counts as a
  sprint hit because the server still has it sprinting then.

**Why 0.13.0 did not fix it.** 0.13.0 added a second drill for range, after the timing drill, and charged
0.5 for a jump that came to nothing. It still jumped at an opponent four blocks away. Two mistakes:

- **The order.** By the end of the timing drill jumping is a habit: it jumps on nine ticks in ten,
  wherever it stands. A habit like that cannot be unlearned one tick at a time, because holding back for
  one tick changes nothing when the next tick jumps anyway, so there is nothing to learn from. This was
  tried, not guessed: a stand-in for the drill, played by the real network and the real learner
  (`RangeLessonTest`), still jumped at the far opponent on nine ticks in ten after 108,000 steps of the
  range drill, even with a wasted jump costing 2.0, four times what 0.13.0 charged. Taught when to jump
  and when not *together*, from a jump key with no habit yet, the same stand-in has both in about 55,000
  steps (four runs: 26 to 33 updates of 1,800 steps). The stand-in is my model of the drill, not the
  game.
- **The price.** At 0.5 against the 3.5 a critical hit earns, a jump was worth making wherever one time
  in eight came off, which is a jump at an opponent four blocks away.

What a punish crit is and when players use it comes from the Minecraft Wiki's PvP tutorial for Java
Edition: a critical hit made with the fall from the opponent's own knockback instead of a jump. It works
when the opponent is still in reach as you come down, which is after a hit that threw you only a little
way (their critical hit, which has no sprint knockback) or when they keep pushing into you. What the game
itself asks of a critical hit was read from the 1.21.11 code: falling, off the ground, not sprinting as
the server knows it, and the sword more than nine tenths back. A hit lifts you only if you were standing
on the ground; you then come down from about the sixth tick to the tenth.

So now there are two drills, each first and alone until it is passed, with the AI's feet and camera held:

1. **Drill 1, the jump.** The opponent stands still, and every four seconds it is somewhere else: half
   of the time right in the AI's face (1.2 to 1.8 blocks), half of the time farther off (2.4 to 6.5
   blocks, which starts at the range sprint hits are made from). In its face, jump and swing on the way
   down. Farther off, leave the jump alone: a jump made there gets no swing and costs 2.0. Passed when
   eight in ten of its hits are critical *and* it jumps on no more than one in a hundred of the ticks
   the opponent is farther off (one such jump in five seconds of standing there; an untaught jump key
   is pressed five times as often). Nothing pushes for the jump any more.
2. **Drill 2, the punish crit.** The opponent comes in close, jumps and hits on the way down, every time,
   and stays on top of the AI afterwards. Here the AI has no jump key either: the only way into the air
   is the opponent's hit, so every critical hit it makes is a punish crit. Being hit costs nothing in
   this drill, since it is held in place. Passed when it takes six in ten of the chances it gets (a
   chance is a hit that knocks it up and leaves the opponent under its crosshair and in reach as it
   comes down with the sword back). If the drill turns out to leave it hardly any chances (fewer than
   one hit in ten over 600 hits), the fault is the drill's: the lesson is left, the chat says so, and
   training goes on.

After the lessons, three fights in ten stay drills, a third each: the two lessons, held as before, and the
punish crit with all its keys. Both held drills have to stay passed for the stage to be passed (0.13.3).

(0.14.0: drill 1 is a different drill now, the crit in its face, played with all its keys against an
opponent that pushes into it, never swings and takes a fifth to a half of a hit's knockback. Half of its
hits there have to be critical. And only the punish crit has to stay passed afterwards. See *What 0.14.0
found* above.)

**What the 0.13.2 report showed, and what 0.13.3 does.** Three things, each with its own cause.

1. *It stopped making critical hits in the stage's own fights* (a jump a minute right after the drills, then
   none). A jump that came to nothing was charged whenever the opponent was out of reach as the AI came
   down, wherever the jump had been made from. In a drill the opponent stands; in a fight it steps away,
   so the jump from right in its face, the one the drill had just taught, was charged nearly every time.
   Now a jump is charged by where it was made from. From in the opponent's face it is never charged,
   whatever became of it. From anywhere farther it costs unless it ends in a critical hit: at that range a
   sprint hit is on and a jump gives it up, so it has to land better than about one time in three to be
   worth it. That is also the line between "crit" and "sprint hit": drawn at in-its-face to start with,
   and moved outwards by the fights only where critical hits really land from farther.

   A stand-in for this was played by the real network and learner first (`RangeLessonTest`). With the
   0.13.2 rule the jump at the opponent in its face fell from 75% of ticks to 4% in 30 updates of
   stand-in fights. Never charging a jump made in reach kept it, but it spread to sprint-hit range (8% of
   ticks), which is how the combos were lost in 0.13.1. With the rule as it is now: 35% in its face, 0.7%
   at sprint-hit range, next to nothing out of reach. The stand-in is a model of the fights, not the game.

2. *The punish-crit lesson was over after 28 chances.* It took all 28, by the way the crit model starts
   out (it keeps the swing for the way down), and that was the lesson passed. Now it is judged over 150
   chances, and after the lessons both drills go on among the fights and have to stay passed. In 0.13.2
   the jump drill was never played again after its lesson, so nothing held the jump up while the fights
   took it down, and nothing showed that it had gone.

3. *Against a person on a server it spammed weak clicks.* Not panic: lag. The network is shown how long
   ago it last landed a hit, and in a fight on someone's server that was set when the opponent was seen
   to flinch, a full round trip after the click (about a quarter of a second at 200 ms). In training the
   answer comes on the same tick. So for five ticks after every good hit it was looking at what, in all
   its training, means "that missed", and it clicked again. That reads in the report as 60 to 74% of
   swings before the sword was back, against 9 to 12% in training. Now it is told of its hit when its own
   game sends a swing that was on the opponent, as a person takes their own click for a hit.

   Two more things in the same place. A hit seen to land was credited to the last click before it, which
   with spam is a weak one; it is now credited to the strongest swing still waiting for its answer. And
   the fights were learned from although the critic could make nothing of them (its guesses were worse
   than no guess at all in all four of those updates), which moved the network a long way on noise. In
   fights against people the acting network now only learns from a batch the critic explained at least a
   fifth of; the critic learns from every batch, so it gets there.

The share of jumps that end in a critical hit is no longer asked for to pass the stage (it was 40%): a jump
made in the opponent's face that the opponent gets away from is not held against the AI any more, so not
against the stage either. Still asked for: 5% of its hits in the stage's own fights critical, and both
drills still passed.

**Why its combos went in 0.13.1, and what 0.13.2 does.** In 0.13.1 drill 1 wanted the jump made whenever
the opponent was within reach, up to 3 blocks. In a fight the opponent is within reach every time a sprint
hit is on. So it jumped then, let go of the sprint as it does in a jump, and made a critical hit or none:
either way nobody is knocked back, and a combo needs knockback. It had been taught *where a critical hit
can be made*, not *where one is the better thing to make*.

That cannot be put right afterwards, for the reason found in 0.13.1: what a drill teaches is a habit, and
a habit is not unlearned tick by tick. But the other direction works. So drill 1 now draws the line as
tight as it can be drawn: jump only at an opponent right in its face, where there is no room for a sprint
hit to do much anyway. From there, real fights can teach it that a jump from a little farther pays as
well, if it does; taking a jump *up* is something it can learn. The drill with the standing opponent and
all its keys is gone for the same reason: with its keys back it could crit that opponent from anywhere in
reach at no cost, and would have learned to jump at sprint-hit range after all.

**The training opponent's own critical hits (0.13.1).** The punish crit can only be learned against an
opponent that really lands critical hits from close up, and up to 0.13.0 the scripted one mostly did not:

- It only swung once its crosshair had been on the AI for its whole reaction time without a break. The
  way down from a jump lasts five ticks, so against an AI that moves it came down again and again
  without swinging.
- It jumped from the edge of its reach, three blocks off, and did not move in the air. By the time it was
  coming down the AI was usually out of its reach.
- After a critical hit it ran straight at the AI, and its next jump was a running jump that carried it
  past.
- And when it did land one from two blocks away, the AI came down about three blocks from it: right at
  the edge of reach, so mostly no punish crit was there to be made.

Now it comes in to a block and a half, jumps only from within 2.2 blocks and from a walk, keeps walking
in on the AI while it is in the air, swings the moment it is coming down with the AI under its crosshair
and its sword back, and afterwards stays within a block or so. `/swordai status` and the report say how
many of the hits the AI took in the drills were critical hits, so whether the opponent is doing its job
can be read off.

What changed in how it plays and what it is paid for:

- **A jump at an opponent out of reach costs 2.0.** That is a jump which was back on the ground without
  a critical hit, with the opponent out of reach all the way down. Where it took off does not matter: a
  jump from four blocks that carries it into reach is not charged. A jump that had the opponent in reach
  and was only mistimed is not charged either, so trying stays cheap where a critical hit is there to be
  had. A critical hit earns 3.5, so a jump has to come off better than one time in three to be worth
  making. Nothing is paid for the jump itself any more, and nothing pushes for it.
- **A swing in the air at nothing costs 1.0.** Only in the air: a swing on the ground that misses still
  costs nothing but the sword's charge. In the air it is the sword thrown away just before landing,
  which is when an opponent that was out of reach arrives. This is what covers being knocked up by a
  hit, where there is no jump to charge for.
- **A combo that does a critical hit's damage is paid as a critical hit.** Each time the sprint hits of
  one combo add up to another critical hit's worth of damage, the AI is paid what a critical hit pays.
  The damage is what the game really dealt: through diamond armour a critical hit does about as much as
  1.7 sprint hits, so the second hit of a combo is the first one paid, and after that about three hits
  in five. A sprint hit that stays alone earns none of it. This is what makes *not* jumping a
  choice with something behind it: where a critical hit is not a sure thing it leaves the jump alone,
  the sprint-hit network plays on, and if its combo comes to as much as the critical hit would have,
  that was the right call. It is paid again for every further critical hit's worth, so a long combo is
  worth more than several short ones and there is nothing to gain by breaking one off.
- **Which of the two it goes for is decided by the crit model**, from how large the opponent is on screen
  and how it is moving: it presses jump, or it does not and the sprint-hit network plays. There is no
  third model for that choice; it is this one's whole job, and drill 1 is where it learns it.
- **In the air, a swing takes two.** The crit model says whether a swing now would be critical (coming
  down, sword back, and now also: not sprinting). The main network, which has spent its whole training
  on whether the opponent is under the crosshair and near enough, says whether there is a hit to be had.
  Where it would not swing, the swing is held back. The main network can only hold it back, never push
  it, and the crit model can learn its way round it.
- **The swing is kept for the way down.** Knocked into the air by a hit, the main network used to swing
  back at once, on the way up, and the punish crit was gone. The swing is now the crit model's for as
  long as the AI is off the ground, also on the way up. Its feet are still the main network's until it
  starts coming down, so it keeps running at the opponent meanwhile; then the crit model lets go of the
  sprint, and one tick later the swing can be critical.
- **A punish crit pays more** than a critical hit from a jump (1.0 on top, was 0.5).

In `/swordai status` and the report: `jc%` is the share of its jumps that ended in a critical hit; `pun%`
is now punish crits per chance (it was per critical hit taken, whether or not anything could be done
about it). Two lines say what the drills and the fights have come to lately: how often it jumped with the
opponent out of reach, how many of its swings in the air were at nothing, how many of the hits it took
were critical hits by the opponent, how many left it a chance, and how often a combo came to a critical
hit's damage.

**Stage 8 is not left without critical hits.** Up to 0.12.0 it could be passed after 300,000 steps
whatever it did, and a run went through it that way without one critical hit. That way out is gone: it
is passed once the two drills are passed and, in the stage's own fights, at least one in twenty of its
hits is critical and four in ten of its jumps end in a critical hit (if it jumps often enough to tell).
The share of critical hits asked is small on purpose: against an opponent that can be comboed, the combo
is often the better way to the same damage. `/swordai stage 9` remains for moving it on by hand.
(0.14.0: one hit in twelve, over the fights against both kinds of opponent, and the punish-crit drill
still passed. Nothing is asked of its jumps.)

**What S is paid for.** In the opponent's combo, every tick of S pays for as long as the opponent is
within three blocks. Once it is farther off than that, S is no longer paid but charged for: backing out
has done its job, and going on with it is running away.

**Fights against real opponents teach it most (0.11.0).** (Not by default since 0.14.0: turn it on with
`/swordai learn on`. See *What 0.14.0 found* above for what it did to a network left to it.)
`/swordai fight` learns by default now (`/swordai learn off` stops that), from 600 steps at a time (half a minute of fighting) instead of 1,800,
and what is left at the end of a fight is learned from too. From stage 7 on such a fight is played with
every key, the jump included, and with getting out of combos and critical hits all paid for, whatever
stage training is in: a real opponent does all of it at once. The pushes of the training stages are not
used in these fights. (From 0.9.0 to 0.10.1 the push towards S was: against an opponent that combos well
the AI was then pushed backwards most of the time, and a fighter walking backwards cannot sprint, so its
sprint hits were gone in fights.)

**Hitting back after being hit (0.11.2).** A swing made in answer to a hit is paid only if the sword was
charged, and a swing before the sword is back costs what it always costs. Up to 0.11.1 both were waived
for a second after every hit taken: the first swing in that second was paid whatever it was, and weak
swings and sweeps were free. Against an opponent that keeps hitting, that second never ends, and what it
taught was clicking in a panic. A report from fights against players showed it: weak swings up to 23% of
all swings and full-charge hits down to 50%, against 3-9% and 81-91% in training.

**Weak hits, being hit, and not fighting (0.12.2).**

- A swing made before the sword is 60% back costs 0.6 (0.15 before), and a weak hit that lands earns
  nothing for the combo and is not counted as part of one. Before, it earned the whole combo bonus, up
  to 0.8, so tapping at an opponent from close up paid better than waiting for the sword.
- A hit taken costs 2.0 in stages 6 to 8 (1.0 before), and the second hit in a row and each after it 1.0
  more. In the duel a hit taken cost only the damage it did, about a third of what a hit given earned;
  it now costs 0.8 and 0.4 more in a combo. That is about what a full sprint hit in a combo earns, and
  deliberately not more: a fighter that loses more by being hit than it can win by hitting stays away.
- Staying away costs too, now in every fighting stage and not only the duel: after five seconds with no
  hit either way, every tick does.

**Critical hits (stage 8).** A critical hit is a swing made while falling, with the sword charged, and
not sprinting as far as the server knows. It does half as much damage again, which through diamond armour
comes to nearly twice, but it has none of a sprint hit's knockback: the opponent is left standing there.
Up to 0.6.1 stage 8 paid a critical hit only from within 1.8 blocks, less than a sprint hit, and charged
for holding jump anywhere else, while the same stage paid it to stand at 2.4 to 3 blocks. So it never
jumped and never learned them. Now:

(The list below is how stage 8 was set up from 0.7.0 to 0.12.3. Since 0.13.0 the jump is no longer paid or
pushed for in the stage's fights, and the stage is passed differently: see *When to crit, when not, and
the punish crit* above. What a critical hit pays and what a late one costs are unchanged.)

- **A critical hit pays more than any other hit**: as much again as a plain hit and half as much more,
  three times what the sprint behind a hit adds. A punish crit (made on the way down from the opponent's
  own hit, without a jump) pays a little more still.
- **The jump is paid for**: taking off three to five ticks after it swings, with the opponent within
  4.5 blocks. A jump is on its way down from its 7th tick to its 11th, and the sword is back 11 ticks
  after the swing, so a jump made then leaves four or five ticks in which a swing is a critical hit.
  Once per swing. (Up to 0.8.0 the jump paid and pushed for was the one made with the swing or right
  after it. That one has landed again by the time the sword is back, with a single tick to hit in,
  which is one reason no critical hits came of it.)
- **And for the first part of the stage it is pushed to make it.** A key that has just been unlocked is
  pressed at random, and a random jump makes a fighter worse, so in 0.7.0 the one thing it learned about
  jumping was not to, long before a jump at the right moment had been tried often enough to tell apart.
  Paying for the right jump did not get past that. So for the first 40,000 steps of stage 8 a push is
  added to the network's own choice in one situation only: three to five ticks after it has swung,
  standing on the ground with the opponent on screen. The push is as strong as it takes for it to press jump on half of those
  ticks; if the network leans against jumping, the push grows. Over the next 40,000 steps the push is let
  down to nothing. It is part of the policy that is learned about, so nothing is learned wrongly from
  it, and the network can lean with it or against it throughout. `jmp/m` in the report is how often it jumps; `/swordai status` and the
  report say how strong the push is.
- **Coming down with the sprint off is paid for**, tick by tick as the sword comes back, at twice what
  having the sprint in place pays. It is paid out of the same few ticks per landed hit, so it is the one
  or the other, and hopping about without hitting earns nothing.
- **Trying is cheap**: a critical hit that comes a tick late is a slow hit on the ground, which costs a
  quarter of what it does in the stages before. Jumping costs nothing within 4.5 blocks; only farther
  away, where no hit can follow.
- The stage is passed once **30% of its hits are critical** (`crit%` in the report) and it lands at
  least three hits for every five it takes, and not before 95,000 steps: the numbers it is passed on
  are all from after the push has gone. The share of sprint hits is no longer asked there, since
  critical hits take from it. If the critical hits have still not come after 300,000 steps in the stage
  it moves on and says so.

Uppercuts (a sprint hit landed while still rising from a sprint-jump) are no longer part of stage 8; the
duel still pays one once a combo is three hits long. In the duel and in self-play a critical hit is paid
a little, and for the rest it is worth what its damage is worth there.

From stage 6 on a **sweep** costs most of a hit's worth: a hit made slowly with no sprint behind it does
full damage but leaves the opponent standing in reach.

Also from stage 6 on, **hitting back while being comboed** pays extra. Once the opponent has landed two
hits in a row, the next hit the AI lands earns a bonus: any hit knocks the opponent back and ends its
combo. That hit is never charged as a sweep. Missed swings still cost nothing. `brk%` in the report is the share of the opponent's combos it
ended this way.

It is also paid a little simply for **answering every hit with a swing**, whether that swing lands or
cuts the air. A fighter that only swings when it is sure of the hit stops swinging exactly when it is being
comboed. The rule:

- One payment per hit taken, for the first swing made within a second of it with the opponent no farther
  off than 3.2 blocks: in reach, give or take. A swing at an opponent that is plainly out of reach answers
  nothing; what to do then is the next thing below. A hit taken costs several times as much, so there is
  nothing to collect by being hit.
- For that same second nothing else about swinging is charged: not swinging before the sword is back, not
  swinging before its plan said, not a slow hit without sprint.
- And the aim counts for the whole second, also while the sword recharges: it has to find the opponent again.

`ans%` in the report is the share of the hits it took that it answered this way.

The other way out is **backing out and coming back with a hit**, for when the opponent is too far to
swing at, as it is right after its hit has knocked the AI back. This is what **stage 7** is about.

- **How: S.** From the second hit in a row, every tick of holding S is paid for the first 12 ticks after
  each hit of the combo (with the opponent within 6 blocks). Those are the ticks that decide it: the hit
  has thrown the AI back, the opponent is running after it, and whether its next swing finds the AI a
  step out of reach or a step inside depends on the key held on the way. A hit's worth of this pay stays
  below what the hit cost, so being hit is never a way to collect it.
- For those same 12 ticks, **running back in with W and sprint is not paid**, as it otherwise is once the
  sword is ready: that is the next hit.
- From the second hit in a row it is also no longer paid for standing at fighting range (in a combo that
  is where the next hit lands), but a little for being just out of reach.
- **Then come back.** Having been out of reach for at least a quarter of a second and then landing a
  fully charged hit, before the opponent lands another, pays the escape bonus: more than hitting back
  from inside the combo does, and in place of that, not on top.
- Hitting back is still paid as before, so when the opponent is in reach it swings, and when it is not it
  backs out.
- The AI can tell the second hit from the first: the number of hits it has taken in a row is one of the
  things it knows about its own body.

Backing off must not become a way to collect, so it is hedged:

- If the opponent's combo simply runs out while the AI is away, the bonus is **held back** until it has
  come back and landed a fully charged hit. If it is hit first, the bonus is gone.
- It only counts as getting away if it was really **out of reach** for a while. A combo that ended with
  it standing where it was earns nothing, and being hit again on the way out starts that count over.
- After an escape that was not followed by a hit, **nothing about backing off is paid again** until it
  has landed a fully charged hit. Backing off, being comboed again and backing off again pays nothing,
  however often.

Being hit once more on the way out does not close the way out: a person's combo is longer than two hits.
(Up to 0.6.0 all of this only began with the fourth hit in a row, and one more hit ended it. Up to 0.6.1
no key was paid for: only where it ended up.)

In the report: `bck%` is the share of those ticks after a combo hit that S was held; `out%` is the share
of the hits of combos on it after which no further hit followed (it got out, by backing away or by
hitting back first); `esc%` is the share of the combos on it that it backed out of and then answered with
a charged hit. Stage 7 is passed once `bck%` is at 50% and `out%` at 55%, with its sprint hits at 40%.
How many hits it lands for every one it takes is not asked in this stage: its opponent is not a fair
one, and getting away from it is the point. It can be passed from 70,000 steps on, after the help with S
(see *Getting a move tried*) has ended. If the habit has not formed after 160,000 steps in the stage
it moves on and says so.

**Combo drills.** A fighter that wins most of its exchanges is rarely the one being comboed, so the two
lessons above would hardly ever come up: against the scripted bot it lands several hits for every one it
takes. From stage 6 on, part of the fights are therefore drills against the only fighter that combos as
well as it does, a saved copy of itself:

- The copy starts a step away, and the AI is held still until the copy has landed its first one to four
  hits (in stage 7 two to four, so that every drill starts inside a combo). Then the AI gets its keys back, in the air, that many hits into a combo. Those first hits are
  real hits but are not charged, since nothing it did brought them on.
- From there the usual rules apply. Two hits behind, a hit back pays as a combo broken, and getting away
  and coming back with a charged hit pays as an escape, with the same hedges as above.
- A drill lasts 10 seconds. In stage 6 half of the fights are drills (about a quarter of the playing
  time, as they are short); in stages 7 and 8 three in ten (in stage 7 the combo to get out of is the
  scripted bot's, since a copy can be traded hits with; in stage 8 the time goes to critical hits); in the
  duel and in self-play a quarter, since there it is comboed often enough anyway.
- Before the duel the copy is made anew every 20,000 steps and at every new stage, so it stays the fighter
  the AI is now.
- In stage 6 the drills **wait until it combos itself**: at least half of its recent hits against the
  scripted bot are sprint hits and its combos are two hits long or more. A fighter new to the stage cannot
  combo, so neither can its copy, and being hit by an equal before it has learned what a sprint hit does
  would only teach it to keep away. Combos are learned against the scripted bot first, as before; the
  drills are added on top once there is something to drill. If its own combos fall apart, the drills stop
  until they are back. From stage 7 on they run from the first fight.
- Drills do **not** count towards passing a stage or towards winning the duel: started from behind, they
  would only pull the numbers down. Only `tkn`, `ans%`, `brk%`, `esc%`, `bck%` and `out%` in the report include them. `tkn` is how
  often the opponent had a combo going on it in that update, so `brk%` and `esc%` can be read as shares of
  something.

**Sprint hits from close.** With the opponent already in its face, no step forward seems needed; but a
hit only knocks back if the server has the attacker sprinting, and that takes W and sprint. Two things
teach it:

- From stage 6 on, in about a third of the fights the bot does not hold its range but keeps pushing in and
  never backs off, like a player who wants to be in your face.
- Having the sprint in place (as the server knows it) while the sword comes back and the opponent is near
  is paid a little, for a few ticks per landed hit, so standing ready without hitting earns nothing.

Stage 6 is not passed until at least half of its hits from under 2.4 blocks are sprint hits (`cls%` in
the report).

In the **duel** and in **self-play** the bot's body is played by a saved copy of the network
(`champion.bin`), run as a real client would be, exactly as in `/swordai spar`. The copy is made from the
network as it is at the moment the stage begins.

- In the duel the copy stays as it is. The stage is passed when the network beats it in 80% of at least 20
  recent fights: it is then clearly better than the fighter that began the duel.
- In self-play the copy is replaced. After every update the fights against it are counted: with 20 or more
  decided and 65% of them won, the network as it is now is saved as the new copy to beat and the count
  starts over. `/swordai status` shows the generation and the score.
- In both, a network that wins 10% or less of 30 fights has lost its way and starts again from the copy.

Fighting only itself has three known traps, and each has a guard:

- **Going round in circles** (style B beats A, C beats B, A beats C). Self-play keeps the last four
  copies it has beaten and plays about three fights in ten against one of them. To take the newest copy's
  place it must also win at least half of 8 or more fights against those older ones.
- **Forgetting other kinds of opponent.** In the duel and in self-play, three fights in ten are against a
  scripted bot from stages 6 to 8: two of the three against the one that jumps in for crits, the rest
  against the one that rushes in and the one that backs off after its hits. (Before stage 9, about one episode in eight replays any earlier stage.)
- **Agreeing not to fight.** After five seconds without a hit from either side, standing off costs more
  per tick than aiming pays. A fight that runs out of time (60 seconds) costs both sides 3, against 5 for
  a knockout. `drw%` in the report is the share of fights that ran out of time.

These guards cover the scripted styles above, not everything a person might do (jump-resetting, for
instance, is not something any training opponent does).

Sword and armour never wear out, and both fighters' kits are checked at the start of every episode.
A sprint hit ends the sprint on the server, and the game only notices a new sprint once the old one has
really stopped, so holding W and sprint lands one knockback hit and then none. Letting go of W after a
hit (the W-tap) is what makes the next hit a sprint hit again; stage 6 needs most hits to be.

A stage is passed when its numbers hold over recent play; `/swordai status` and the report say what is
still missing. Rewards and thresholds live in `swordai/config.json`.

## Files

Everything is in `<game folder>/swordai`: `checkpoint.bin`, `curriculum.properties`, `config.json`,
`metrics.csv` (one row per update), `champion.bin` (self-play), `drill-copy.bin` (combo drills),
`checkpoint-before-upgrade.bin` (see *Updating the mod*), `before-headless/` (see *Training faster*),
`before-people/` (see *Learning from people*), `inbox.txt` (commands for it, see *Running it with nobody
at the keyboard*; it is deleted once read), `report.txt`, `crit-drills.txt` (what the crit drills had come to when training last ran, so a report made
after a fight still has it), `last-error.txt`,
`last-fight.txt`, `debug/`.

## Build

Loom needs Java 25 to run; the mod itself targets Java 21.

```
JAVA_HOME=/path/to/jdk-25 ./gradlew build
```

The jar is written to `build/libs/`. `core/` is plain Java with no Minecraft on its classpath
(network, PPO, vision, curriculum) and has its own tests: `./gradlew :core:test`.

Carpet Mod is by gnembon and contributors (MIT).
