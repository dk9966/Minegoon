# Long tasks

Written October 13, 2026. This is the suite we run when we want to know whether the helper can finish a real trip, not a single action. It follows the method in `04-testing-dialogue.md`: same world, a finish line the game can check, several repeats, and a look at what the helper actually did.

The helper already knows how to mine, craft, smelt, hunt, grow, fill a bucket, sleep, and come back. These tasks are the long versions of that. Each one starts from an empty inventory at spawn, on the fixed seed `minegoon`, in the morning. Success is the result line the plugin writes when the items are in its inventory and the task is done. Nether, the End, building, and armor are not in this suite: the helper has no code for them yet, so a failure would not tell us what to fix.

---

## The tasks

| Task | Request | Why it is worth a run |
|---|---|---|
| Stone pick | `1 stone_pickaxe` | The whole early game in one request: punch a tree, make a table, mine stone, craft the pick. If the hands or the recipe walk are wrong, it shows up here in a few minutes instead of halfway through a diamond trip. |
| Iron | `3 iron_ingot` | The first trip that goes underground, places a furnace, and has to keep fuel. This is where it gets lost, smelts nothing, or dies. |
| Bucket | `1 bucket` | Three ingots have to become one crafted item. A helper that stops once it "has iron" passes the smelt task and still fails this. |
| Shield | `1 shield` | Iron plus planks, and the result is not a tool. Catches a craft path that only knows pickaxes. |
| Torches | `32 torch` | Quantity. Coal and sticks have to be gathered in bulk and crafted in batches. |
| Water | `1 water_bucket` | A different action from mining: make a bucket, find a source, and use it. |
| Beef | `4 cooked_beef` | Hunt a passive animal, then cook the drop. Tests that food is actually smelted, not left raw. |
| Bread | `3 bread` | The farm. Seeds, a hoe, planting, bone meal, harvest, then the bread recipe. Nothing else in the suite grows a crop. |
| Bed | `1 white_bed` | Wool comes from sheep, then a craft. Night survival depends on a bed, so a failure here means it cannot sleep. |
| String | `4 string` | The trip that has to fight something which fights back. Spiders are a source the helper claims to know. |
| Diamonds | `3 diamond` | The long trip the helper is for: an iron pick, a way down, the ore, and coming back alive. The MVP bar is still 5 diamonds; 3 is the same trip, short enough to repeat. |

Each task is run 3 times. One run is luck. Three is enough to tell "this works" from "this fell over once."

---

## How the runs stay small

A live trip holds about 0.4 GB of world data and uses about 4 ms of a 50 ms tick. The other 2 GB in an old test server was Java reserving a heap it was not using, once per server. The blocks a trip digs cannot be shared, because each trip has to start from the same untouched ground. The server that loads those blocks can.

So the suite does this instead:

- Stop the usual practice server for the duration. Its world and its paused task are left as they are.
- Run **one** Paper server. Three trips share that process. Each trip gets its own copy of the fresh world (`run_a`, `run_b`, `run_c`) and its own helper (`Builder-a`, and so on). The heap is 2.5 GB, with a hard 4 GB cap so a stuck server cannot eat the machine.
- The server's own overworld is a flat pad nobody plays on. The nether and the end are off. The trips never go there.
- Before every trip, unload that one world, put the fresh copy back, and load it again. The other two trips keep going. Copying the world is small. Restarting Paper was the slow part, and a trip no longer waits on it.
- Set the time to morning, clear the weather, and clear hostile mobs already standing around before the trip starts. Animals stay, since they hardly ever respawn. Mobs that wander up during the trip are part of the trip.

The same world copy is kept for later iterations, so a better score is the helper changing, not a kinder seed. Every lane copies that one world, so they share its spawn. A fresh world does not move the player back, so before each trip the helper is moved to its start spot.

```
scripts/long-tasks.sh 1                      # the long suite, pass 1
scripts/long-tasks.sh 4 skills               # the skills suite, pass 4
scripts/long-tasks.sh 5 skills iron,water    # only some skills
```

Paper 26.3 moves a world folder into `world/dimensions/minecraft/<name>` the first time it loads, and refuses to do it again if that folder is already there. Putting a fresh copy back therefore also deletes the moved folder.

The Java on PATH is 20, and the plugin build will not start on that. The script builds with the JDK 25 already in `~/.local/jdks`.

That builds the plugin, prepares the world once, and writes one folder per trip under `scripts/results/long-tasks/iter-1/`. That folder is gitignored. The scores and the notes below are the record that stays in git.

Each trip records the result line (done or not, seconds, deaths, why it stopped), what the helper said, the job log (plan, each step, failures), and a position sample every 30 seconds. A trip that sits on the same block for two minutes is marked stood-still.

---

## What a pass does

1. Run all 33 trips.
2. Read the failures, and one or two successes. The note to keep is the first thing that went wrong, not the last.
3. Change that one thing.
4. Run the same 33 trips again.

Three passes. After the third, stop and write down what is still broken.

---

## Iteration 1

Ran October 13, 2026, on the seed `minegoon` world, helper starting empty at spawn. Two servers. The plugin was the one already in the tree, before the path change below.

Stone pickaxes are the only thing it finished: 2 of 3, in 130s and 296s. The third made the wooden pick, took a hit underground, came home at low health, and stood at spawn until the time limit because the test never says resume.

Everything else failed. The first thing that went wrong, over and over, was drowning. Searching for stone walks into flooded caves. The helper does not swim, but the route still stepped through water with its head under, and it drowned. Going back for the dropped items walked into that same water and drowned again. Three deaths ends the task. Iron did this 3 of 3 times. Bread, beef, and the bucket did it too. A few trips survived the water and then got stuck on the next real gap: no iron ore in sight (water, one run), no sheep (bed, one run), no wheat to harvest (bread plans a harvest, and there is no farm here).

Two trips at the end never started. After a long drown-and-wait, both servers were slow to come back and the harness gave up at three minutes. String has one run, diamonds two, bed is missing its second. Those missing runs look like the same drowning, not a new problem.

The change for the next pass: a step whose head would be underwater is not a step. Wading through water one block deep is still allowed. The harness also ends a trip when the helper is home and paused, instead of sitting out the rest of the time limit, and it waits longer for a server to boot.

| Task | Done | What the runs did |
|---|---|---|
| stone-pick | 2/3 | 130s and 296s done. The miss came home hurt and waited. |
| iron | 0/3 | Drowned looking for stone. One died three times in 202s. |
| bucket | 0/3 | Drowned, then either waited at home or could not place a crafting table. |
| shield | 0/3 | Drowned, or walked until the time limit without finding stone. |
| torches | 0/3 | Often reached the coal step, then came home hurt and waited. |
| water | 0/3 | One made a stone pick and then found no iron ore. The others drowned or waited. |
| beef | 0/3 | Died three times (a skeleton, then drowning on the way back). |
| bread | 0/3 | Planned to harvest wheat, found none, drowned while looking. |
| bed | 0/2 | One found no sheep. One came home hurt and waited. The third server boot failed. |
| string | 0/1 | Drowned once, then could not path out of the hole. The other two never started. |
| diamonds | 0/1 | Drowned, came home, and the server sat there until the harness unstuck. The other boot failed. |

## Iteration 2

Ran October 13, 2026, after the no-underwater-steps change. Nobody drowned. Two trips finished out of 33.

| Task | Done | What went wrong first |
|---|---|---|
| stone-pick | 2/3 | No stone in sight; the model said "explore" and the walk went nowhere. |
| iron | 0/3 | "I can't find a way out of here to explore", then stood still for minutes. |
| bucket | 0/3 | Same, and the model kept switching to "another source" that did not exist. |
| shield | 1/3 | Stuck looking for cobblestone, or for bamboo. |
| torches | 0/3 | Stood still, or replanned a pickaxe it had just lost. |
| water | 0/3 | Could not path out to explore. |
| beef | 0/3 | Found no cows. One run died three times. |
| bread | 0/3 | No wheat in range, and "no room to place crafting_table". |
| bed | 0/3 | No sheep, or stood still. |
| string | 0/3 | Could not path out, or stayed hurt. |
| diamonds | 0/3 | Found no iron ore in reach. |

The pattern was the same everywhere. The helper only knew one way to look: scan what is around, then walk somewhere and scan again. When that walk failed it was stuck, and the model, asked before every step, picked between menu items that all led back to the same walk. Three failures ended the task. This pass led to the redesign in `04-mvp-plan.md`: a knowledge file of where things are and how a player would look for them, sight out to render distance, no giving up, and the model only for new ideas.

## Iteration 3

Not run as a long pass. The long trips take an hour per pass and fail for one reason at a time, so the next passes used the skills suite below.

---

## The skills suite

One skill per task, from a head start, with short time limits. A failure points at one piece of the helper, and a pass of all 33 trips takes about an hour on three lanes instead of three.

| Task | Request | Kit | Why |
|---|---|---|---|
| stone | 16 cobblestone | wooden pickaxe | Finding exposed stone, or digging down to it. |
| coal | 8 coal | stone pickaxe | An ore that is common on rock faces. |
| iron | 3 raw_iron | stone pickaxe | The ore that needs the knowledge most: rock faces, peaks, caves, then stairs to y=14. |
| cow | 3 beef | none | Spotting animals, or walking to the biomes they live in. |
| sheep | 2 white_wool | none | The same, for a rarer animal. |
| water | 1 water_bucket | bucket | Finding a source and using the bucket on it. |
| diamonds | 1 diamond | iron pickaxe, stone axe, shield, food, blocks | The deep trip: kit check, then down the caves to y=-59 and through them. |
| spar-zombie, -skeleton, -spider, -creeper | win a fight | stone sword, shield | The fighter against each kind of mob, spawned 8 blocks away. |
| spar-skeleton-bow | win a fight | stone sword, shield, bow, 32 arrows | Shooting with the arrow's drop allowed for. |
| bedrock | 1 bedrock | none | Impossible: the model should refuse it up front. |
| amethyst | 1 amethyst_shard | the diamonds kit | Nothing in the planner's tables: the model has to say where it comes from. Then the same cave trip as diamonds. |
| trek, climb, pit, ravine, gap | reach a spot | none | The moves courses: a 300-block walk, a cliff, a 6-deep pit, a ravine, a gap. |

The three repeats start from different places: spawn, (480, -320), and (-560, 640).

### Skills results

Pass 3 was the first full pass after the redesign:

| Task | Done | Notes |
|---|---|---|
| stone | 2/3 | The spawn run timed out underground. |
| coal | 1/3 | "I can't find a way out of here to new ground." |
| iron | 0/3 | Stood still for minutes, or found no way out. |
| cow | 2/3 | |
| sheep | 1/3 | |
| water | 0/3 | The bucket was used on the block, not the water. Fixed: a bucket fills by looking at the water and using the item. |
| diamonds | 0/3 | A loop: the helper kept asking for a replacement pickaxe it already held. Another one alternated "I'm hurt" and "nothing to eat". |
| spar-zombie | 3/3 | Every swing landed, no damage taken. |
| spar-skeleton | 1/3 | The other two dealt damage but did not finish in time. |
| spar-spider | 3/3 | |
| spar-creeper | 2/3 | No explosions. |

Passes 4 to 7 were iron and water only, each fixing what the previous one showed:

- **Water: 3 of 3** once the bucket was used the right way.
- **The spawn mystery was the harness.** A fresh world does not move the player back, so every trip on lane a started where the last one ended, often deep underground. Every lane now moves its helper to the start spot first.
- **Respawning in the wrong world.** A helper that died came back in the server's own flat world, could not walk back to its items, and was killed by slimes there. It now respawns in its own world.
- **Looking into caves from the surface.** Near spawn most stone with an open face is inside caves under the ground. Every one of those cost a line-of-sight check, and the search ran out of checks before it reached the stone it could see. In daylight, a far block now has to get some light from the sky to count. Underground, all blocks count.
- **Walks to viewpoints.** A walk to a cliff or peak often ended a few blocks short, below the top, and was counted as a failure. Ending within 8 blocks now counts. Places already walked to are kept for the whole session, so a restarted search does not walk back to them.
- **Stuck on a pillar.** A staircase cut down from a peak can leave the helper on one block with a drop on every side. It now digs down a block and goes on from there.
- **Meals.** A meal now fills up to 18, the level at which health comes back, and eats what it carries before it hunts.
- **Pickaxes.** Iron trips carry two pickaxes, and a supply short of its count asks for the whole count, not the shortfall, so the planner does not think it is already done.

Iron went from 0 of 3 to 1 of 3 in these passes. The successful runs took 5 to 6 minutes. The rest ran out of time walking: a walk across rough ground to a viewpoint 60 blocks away took 30 to 60 seconds, two to four times walking speed.

Pass 8 was the full suite on all of the above:

| Task | Pass 3 | Pass 8 | What still went wrong in pass 8 |
|---|---|---|---|
| stone | 2/3 | 2/3 | One run timed out still digging. |
| coal | 1/3 | 2/3 | "I can't find a way out of here to new ground." |
| iron | 0/3 | 2/3 | The miss already held its 3 raw iron, then started replacing a worn pickaxe before noticing. |
| cow | 2/3 | 2/3 | No cows from spawn after four places. |
| sheep | 1/3 | 1/3 | No sheep after four places. |
| water | 0/3 | 2/3 | The miss tried the water of one lake block by block, without a word, for three minutes. |
| diamonds | 0/3 | 0/3 | One found its diamond 15 seconds before the time limit. Two wore out the iron pickaxe on the stairs down. |
| spar-zombie | 3/3 | 2/3 | The loss took 19 damage in one exchange. |
| spar-skeleton | 1/3 | 3/3 | |
| spar-spider | 3/3 | 3/3 | |
| spar-creeper | 2/3 | 2/3 | No explosions; one ran out of time. |

The fixes after pass 8:

- **A job already done is finished before anything is prepared for it,** such as a tool replacement.
- **A target the helper cannot get to rules out its neighbours too,** and after three such misses in a row it looks from somewhere else. A lake is no longer tried one block at a time.
- **Iron and better tools are spared** when a cheaper tool also suits the block. The stairs from y=70 to y=-59 are about 400 blocks of digging, and an iron pickaxe lasts 250. The diamond kit now includes two stone pickaxes for the way down.

Pass 9 (diamonds only, before the tool change) was 0 of 3 again. Night fell about ten minutes in. One helper was on the surface replacing its worn iron pickaxe, so it went after a bed, and the bed needed wool from sheep it could not find. Pass 10 was stone, iron, water, and diamonds on those fixes:

| Task | Done | What happened |
|---|---|---|
| stone | 1/3 | Both misses held enough cobblestone and then could not "bring it back". |
| iron | 2/3 | 140s and 167s, the fastest yet. The third did not start: the harness could not reset the lane. |
| water | 1/2 | The miss had its bucket of water and could not bring it back either. |
| diamonds | 0/2 | One reached y=-59 in three and a half minutes, then ran out of stone pickaxes, wore out the iron one, and spent the rest looking for iron. The other died at night, came back with nothing, and died four more times to the zombies waiting at spawn. |

The "bring it back" misses were the start spot. `spreadplayers` puts the helper on the highest block, which in a jungle is the top of a tree, so home was a treetop the helper cannot climb back to. It now counts as back once it is within 8 blocks sideways, at any height, and a helper spawns on the highest block that is not leaves. The diamond kit is now four stone pickaxes, about 520 blocks of digging. Neither change has had a pass yet.

### Passes 11 to 13: mobility and the open problems

Pass 10 left five problems: deaths at night, slow walking, no climbing, worn tools, and a false "no sword at night". The mobility rewrite and fixes in `04-mvp-plan.md` ("second half") covered them, and passes 11 to 13 ran the whole suite on them. Each pass found a few new bugs, which were fixed before the next.

| Task | Pass 11 | Pass 12 | Pass 13 |
|---|---|---|---|
| stone | 2/3 | 2/3 | 3/3 |
| coal | 1/3 | 0/3 | 2/3 |
| iron | 0/3 | 2/3 | 2/3 |
| cow | 1/3 | 1/3 | 2/3 |
| sheep | 1/3 | 1/3 | 0/3 |
| water | 3/3 | 2/3 | 3/3 |
| diamonds | 0/3 | 1/3 | 0/3 |
| spar-zombie | 2/3 | 2/3 | 3/3 |
| spar-skeleton | 2/3 | 3/3 | 2/3 |
| spar-spider | 2/3 | 3/3 | 3/3 |
| spar-creeper | 2/3 | 3/3 | 3/3 |
| spar-skeleton-bow | 3/3 | 1/3 | 3/3 |
| bedrock (refused) | 3/3 | 3/3 | 3/3 |
| amethyst | 0/3 | 0/3 | 0/3 |
| trek | 3/3 | 0/3 | 2/3 |
| climb | 3/3 | 3/3 | 3/3 |
| pit | 3/3 | 2/3 | 3/3 |
| ravine | 3/3 | 2/3 | 3/3 |
| gap | 3/3 | 2/2 | 3/3 |
| **all** | **38/57** | **34/56** | **45/57** |

What each pass showed, and the fix:

- **Pass 11, iron 0 of 3 and diamonds 0 of 3: stuck on one block for 8 to 15 minutes.** The worn-tool check counted "holds no tool for this" as "the last tool is wearing out". Logs need no tool, so gathering wood for a new pickaxe asked for a new tool on every check, forever. It now stops only when the tools it held are worn, or the last one broke.
- **Pass 11, amethyst refused in 3 seconds.** The model said "yes, from amethyst clusters in geodes", but that only told the helper where to look, not what to mine, so the planner still had no source. The model's answer now also names the blocks the item drops from, and the planner learns them. In passes 12 and 13 it planned the trip and searched, but found no geode in 8 minutes.
- **Pass 11, lane a's melee spars "unfinished" with no swings.** On the mountain start the mob spawned on a ledge the fighter could not walk straight to. When the straight approach stalls and there is no bow, the fighter now walks a planned route round to it, up to three times. Pass 13 had one such miss, down from four.
- **Pass 12, trek 0 of 3 and many coal and cow walks stuck on vines.** The "hold on to a vine while idle" fix also fired on climb-downs, where the executor deliberately presses nothing. The helper hung on the vine. Holding on now happens only when nothing at all is driving.
- **Pass 12, cow and coal walks repeating the same dig.** A route would plan through a block the live world then refused to open (the break policy, or water behind gravel), fail, and plan the same route again. Cells the live world refuses are now closed for the rest of that walk.
- **Pass 12, a drowning.** While nothing drives, for example during a dig or the half second spent planning, the helper sank. It now treads water when idle with its head under. Built after pass 13 and not run in a pass yet.
- **Pass 13, lane a stuck on giant jungle trees.** A log only counted as part of a tree if natural leaves were within 6 blocks above it. A giant jungle trunk runs 20 or more logs before its canopy, so the helper was not allowed to break its way off one. The check now follows the trunk up to its top. Also built after pass 13.

### Still broken after pass 13

- **Diamonds, 0 of 3.** The trip down works. The misses are the trip back from y=-59 ("I couldn't find a way back"), deaths underground, and one run that needed logs it could not reach. Fifteen minutes is tight for the whole round trip.
- **Sheep, 0 of 3.** No sheep in sight from 4 places. After "I'll get that another way", the planner tried string from spiders or bone meal from skeletons, which is not easier.
- **Steps that never finish.** Some step-ups, pillars and swims run out of time with the body pressed at the edge of the next block. A replan usually gets round it. The cause is not found.
- **Amethyst.** Planned correctly now, but geodes are hidden in stone, and the helper does not dig toward what it cannot see.
- **Trek on lane b** ended twice with no position recorded, after the helper died in the ocean. That run's result is the harness reporting a death badly, not a walk.

### Passes 14 to 21: real game speed

The short version: passes 11 to 13 were flattering. The helper's walking, mining and fighting loops ran as fast as the worker thread could hand work to the server, up to three a tick, so it mined about three times faster than a player can. Pass 14 put every loop on one real game tick. Mining is now exactly vanilla speed, every per-step time limit costs three times the real time it did, and the gathering scores fell. The passes after that fixed what real speed exposed, which was mostly jungle: vines, canopies, giant trees. Scores from before pass 14 are not comparable with scores after it.

| Task | Pass 13 | Pass 14 | Pass 20 (1 repeat) |
|---|---|---|---|
| stone | 3/3 | 1/3 | 1/1 |
| coal | 2/3 | 0/3 | 1/1 |
| iron | 2/3 | 0/3 | 0/1, died to a creeper walking home with the iron |
| cow | 2/3 | 2/3 | 0/1, stuck on a vine |
| sheep | 0/3 | 1/3 | 0/1 |
| water | 3/3 | 3/3 | 1/1 |
| diamonds | 0/3 | 0/3 | 1/1, in 842s |
| the five spars | 14/15 | 13/15 | 5/5 |
| bedrock (refused) | 3/3 | 3/3 | 1/1 |
| amethyst | 0/3 | 0/3 | 0/1 |
| trek, climb, pit, ravine, gap | 14/15 | 14/15 | 4/5, the pit was carved in a treetop |
| **all** | **45/57** | **37/57** | **14/19** |

Passes 18, 19 and 21 were small checks of single fixes. Pass 21 (iron, cow and the four built courses) got 5 of 6: iron brought its 3 raw iron back, and the only miss was cow on a vine. Pass 22, the full three-repeat check, ran while the machine was busy (load average about 35, with a game client and a video call open). The server stopped answering and the run is not a result.

What changed, and why:

- **One loop, one tick.** `MainThread.tick` waits for the next server tick; walking, mining, fighting and settling all use it. A measured mine now takes as many ticks as the game says it should.
- **Feet from what holds the body up** (`actions/Footing.java`). A body resting on the edge of a block, or holding on at the top of a vine, used to get a feet cell with no moves out of it, so every walk failed at once.
- **The step clock only restarts on progress.** A body slipping back and forth between two steps reset the "too long on this step" clock and the dig limit every time, so one walk could hang for six minutes without a word. Now only a step never reached before gets a fresh clock.
- **Vines.** Sneaking to hold on is only for a body hanging in the air; on the ground it pinned the body at the edge of a leaf block. A climb down presses nothing while on a vine, because in vanilla pressing into anything on a vine climbs up. A body counts as centred only within 0.15 of the middle, so its 0.6-wide box is inside its own column and no block beside it catches its head on a climb.
- **Sand and gravel refill a dug cell.** The executor digs a cell again from wherever the body stands, not only from the step's start.
- **Canopies are not "buried".** The search for new ground measured depth from the highest block, which in a jungle is leaves, so it tunnelled through trees. It now measures from the first block that is not leaves or logs.
- **Getting out from deep down.** When every walk out fails, the search for new ground and the walk home cut stairs up toward the surface or home.
- **Magma.** "Discovered the floor was lava" is the magma block's death message. The reflex that climbs out of lava now also steps off any burning block the body rests on, using the game's own "block under me", which can be beside the feet.
- **The walk home fights and heals.** It used no checkpoint at all, so a creeper met on the way back was never fought. It now fights and heals, but nothing it lacks, like food, stops it.
- **Fewer side trips.** A failed recovery of dropped items is given up instead of retried. A craft or smelt that is missing something replans at once. The "another way" switch does not swap a task for one that hunts monsters, unless it is night. A bed at night is made only from what the helper holds. The model is asked only when a search found nothing. The spare pickaxe for a mining trip is made only from what the helper holds, which in a jungle saved four minutes of chopping.
- **Harness.** Each result records the real code revision, including uncommitted changes. A lane reset is retried once. A death on a trek is reported as one. The helper respawns where the trip started. `REPEATS=n` sets the number of repeats. The built courses are carved into the ground under the start, not into a treetop.

### Still broken after pass 21

- **No clean full pass at real speed.** The next full skills pass, on an idle machine, is the real score for all of the above.
- **Vines are still the most common stall.** Pass 21's cow run stuck on a vine climb before the centring change; that change has not had a pass yet.
- **Server-thread database writes.** Stopping a task writes its status to SQLite on the server thread. On a busy machine that write blocked the server for over 10 seconds.
- **Sheep, amethyst, and diamonds** are being worked on separately.

### After pass 13: moves pass 8 and skills pass 30

Two chats worked on these at the same time. This one took movement, the harness and sheep. The other took diamonds and amethyst.

- **A trek that ends in death** is now reported as `died`, with one death and "died on the way to ...". Before, it showed no position.
- **Clearing mobs killed the animals.** Building the template ran `kill @e[type=!player]`, and every lane reset removed every entity that was not a player. Sheep and cows hardly ever respawn, so lane a's spawn area had none left. Now only hostile mobs are cleared (Bukkit's `Enemy`). The template's `.ready` file holds a version string, so a template made the old way is rebuilt from the same seed.
- **Wet pillars** (pass 13's pit r2) are fixed: moves pass 8 had no step that timed out at a block edge. The one ASCEND that never jumped could not be rebuilt by hand. A one-block dug stair, with stone over the step, over the start, and in a one-wide tunnel, climbed every time.
- **Hanging on to vines while standing.** On a step onto a vine cell, the "hold on" key is the sneak key, and sneaking stops the body at the edge of the block it stands on. Jungle runs stalled with the body 0.30 into the next block, five timeouts in a row. Holding on now waits until the body is off the ground.
- **Moves pass 8:** trek 2/3 (one death in the ocean), climb 3/3, pit 2/3, ravine 3/3, gap 3/3.
- **Skills pass 30** (fresh template, animals kept): sheep 1/3, cow 2/3. The jungle start no longer gets stuck between giant trunks. It now walks, but the vine stalls above made it slow. Lane b's sheep run still found none from 4 places. That search lives in `Prospector`.

### Caves for ores, biomes for sheep

Written October 16, 2026. Not run in a pass yet.

The diamond and sheep misses had the same root: the helper was not looking where a player would. Across every saved run the search took 847 `dig` moves and 18 `cave` moves. Diamonds never went down a cave. They cut stairs to y=-59 and strip mined. Sheep runs walked into biomes where white sheep are rare.

- **The cave scan almost never saw a cave.** It stopped each column at the first open cell under cover. That cell is the one under the cave ceiling, so the "room to stand" check failed and the column was dropped. Now every floor in a column counts, and `Vision.caves` and the new `Vision.floors` share that one column walk.
- **A new move, `cavern`.** Underground, the helper walks through the cave to the floor in sight that is furthest from every place it has already looked from. Only a large cave lets the eyes reach far, so this favours big caves, and the far end shows the most new wall. It stays near the best height and does not climb back above where it already is.
- **No strip mining for ores.** Every ore now goes cliff, peak, cave, and cavern in some order. Diamonds, redstone, and amethyst are `[cave, cavern, peak]`: down the caves in sight, through them at depth, and from the surface toward mountains, where cave openings are more common. `dig` is only for rock such as stone and deepslate. The model can no longer suggest `dig`, and a saved lesson that holds it has it removed when applied. The saved diamond lesson was "cave, then dig", with 1 win and 14 misses, and it went first on every run.
- **Deep-trip gear.** It is now an iron pickaxe, an axe, a shield, 6 cooked food, and 32 blocks. It used to be four stone pickaxes, a sword, and iron armour. The stone pickaxes were for the stairs, which are gone, and the armour cost 13 iron. Amethyst uses the same trip. An axe now counts as a weapon, so night does not ask for a sword as well. The skills kit for diamonds and amethyst matches.
- **Sheep by biome.** Since 1.21.5 a sheep's colour follows its biome. White is common only in temperate biomes (plains, meadow, cherry grove, the forests, swamp). Cold biomes have mostly black sheep and warm ones mostly brown. The sheep list held taiga, savanna, and windswept hills, where the hunt passed over nearly every sheep it saw. Now it lists only the temperate ones.
- **Roaming inside the right biome.** `roam` used to drop the biome the helper stood in, so in a plains with no sheep in sight it never walked further into the plains. It now goes to the nearest ground of a listed biome that is half a sight (64 blocks) from every place already looked from.

Still open: there is no way to order strip mining yet, so it never happens. Underground with nothing new in sight, the search for new ground still tunnels 48 blocks toward unseen chunks. That is a way to reach the next cave, not a search by tunnel. Removing `dig` from iron and coal may cost runs that start on flat ground with no cave in sight. The next skills pass will show it.

### Never drowning: moves passes 10 to 13

Written October 16, 2026.

Every drowning in the saved runs had the same cause: nothing watched the helper's air. Three ways it happened: it stopped to heal from the drowning damage itself and waited under water; it was caught under the roof of a flooded cave; and it sat on the sea floor while every walk failed at once, because no route can start with the head under water.

- **An air reflex** (`Reflexes.breathe`). Every tick the eyes are under water, it works out the swim to the nearest place the head is out (`world/path/Air.java`, a search through cells the whole body fits in, up to 12 blocks away). It sets off once the air left would only just cover that swim with 5 seconds to spare, or at once when there is no air within reach. Then it treads water until its air is full. It goes before fights and every job, after getting out of lava.
- **Walks start by surfacing the same way** (`Mover.surface`, using `actions/Surfacing.java`). It used to hold jump for 2 seconds and dig upward, which under a cave roof just bumped the rock. It now swims to the nearest air, and digs upward only when there is none in reach.
- **Two new courses** in the moves suite. `swim` is a 20-block lake whose surface is a block below the banks. `breath` starts on the floor of a pool under an obsidian lid, with the only air a hole six blocks off, and the goal on the bank past it.

| Course | Before | After |
|---|---|---|
| swim | 3/3 | 3/3 |
| breath | 0/3, drowned in 28s every time | 3/3, in about 12s |
| breath with no job, so only the reflex can act | - | 3/3 alive at full health after 60s |
| trek (crosses an ocean) | 3/3 | - |

Still open: a flooded space with no air within 12 blocks and a roof that cannot be dug. The reflex then just swims up and hopes. A player would place a block for an air pocket, or dig sideways toward air.

## The agent suite

Written October 20, 2026. No pass has been run yet. Record each pass here like the ones above.

The other suites type a command. This one says a sentence to the helper by name, through `/minegoon ask`, so a model reads it (see "Phase: the agent loop" in `04-mvp-plan.md`). It checks two things: that the helper does what the sentence asked, and that the typed commands for the same work still run as before.

```
scripts/long-tasks.sh 1 agent                       # every agent task
scripts/long-tasks.sh 2 agent say-wool,sheep        # some of them
```

It needs `openrouter.api-key` in the lab server's `plugins/Minegoon/config.yml`. Without it, the first spoken trip stops the lane and says so.

With no key, `scripts/scripted_model.py` can stand in for the models. It answers each desk turn from words in the sentence, and each mission with a fixed plan for its request. That checks that chat, the desk, missions, tools and results work in a real game, but not what a real model would choose. Run it on the machine, then set `api-key: stand-in` and `url: http://172.17.0.1:8765/api/v1/chat/completions` under `openrouter:` in `infra/data/plugins/Minegoon/config.yml` before the template world is made. Every request and reply goes to `scripts/scripted-model.jsonl`.

`scripts/live_model.py` is the same kind of endpoint, except it does not choose. Each briefing is written to `scripts/live-model/pending/`, and the call waits until a matching file appears under `scripts/live-model/answers/`. That is how pass 2 ran without an OpenRouter key. The lab container reaches the host at `host.docker.internal` (see `extra_hosts` in the lab compose file).

| Task | Said or typed | Passes when |
|---|---|---|
| say-strip-mine | "strip mine for 1 diamond", with the deep kit | Done, and some `get_items` call ordered `dig` |
| say-wool | "get 2 white wool from the plains" | Done, and some `get_items` call ordered the `plains` biome |
| say-logs-stone | "get 4 logs and 4 cobblestone, then come back to me" | Done, with two `get_items` calls and then `come_to` |
| say-carrying | "what are you carrying?" | One desk turn, `inventory`, and no mission |
| say-bedrock | "get me bedrock" | No mission, and it says why it cannot |
| diamonds | `get 1 diamond`, with the deep kit | Done, as in the skills suite |
| sheep | `get 2 white_wool` | Done, as in the skills suite |
| bedrock | `get 1 bedrock` | Stopped with a reason, as in the skills suite |

The two-item trip and the question have no typed command of their own, so they have no typed twin.

A spoken trip ends at the first of these lines in `results.jsonl`: its mission's line, the line of the task a plain get started, or a desk turn that started neither. Each record also keeps the mission's calls, its turns, its planner calls, and the cost of every model call in the trip, desk turns included. The summary's notes show turns, planner calls and cost for each run, and what went wrong for a run that failed. The trip's text file lists each call and whether the game confirmed it.

### Agent pass 1, with the stand-in model

October 20, 2026. One repeat, from spawn, with `scripts/scripted_model.py` instead of a real model. So this pass shows that the parts work together in a real game, not how good a real model's choices are.

| Task | Done | Time (s) | Notes |
|---|---|---|---|
| say-strip-mine | 1/1 | 779 | 3 turns, 1 planner call. The search went `dig` 7 times, then `peak` twice from the knowledge file. |
| say-wool | 1/1 | 414 | 3 turns, 1 planner call. The task carried the `plains` order. |
| say-logs-stone | 1/1 | 257 | 5 turns, 1 planner call. The planner wrote the plan; the desk model made the four calls after it. |
| say-carrying | 1/1 | 5 | One desk turn: "Holding nothing." No mission. |
| say-bedrock | 1/1 | 5 | One desk answer saying why. No mission. |
| diamonds (typed) | 0/1 | 903 | Stuck on jungle vines at -122 87 -55, the stall in "Still broken after pass 21". |
| sheep (typed) | 0/1 | 302 | Timed out still roaming for sheep, as in skills pass 30. The spoken wool trip took 414 s, so the 5-minute limit is part of it. |
| bedrock (typed) | 1/1 | 1 | Refused up front, as before. |

Both typed failures are open problems in the task code from before this phase; the typed gets ran the same task code and wrote their result lines as before. Two more checks by hand on the same server:

- **Stop during a mission.** `stop` while the first `get_items` ran ended the mission and its task, both "stopped by a player", each with its own result line.
- **Restart during a mission.** Restarting the server while the first `get_items` ran: the helper said "Picking up where I left off", the same task carried on, and the mission finished in 5 turns with no repeated call.

One thing to watch with a real model: every line of the mission log repeats the helper's state ("Now at ... in jungle, health ..."), so a long mission's briefing grows by about that much per turn.

### Agent pass 2, with grok-4.7

October 21, 2026. One repeat, from spawn, on the seed `minegoon` world. There was no OpenRouter key on this machine, so the lab's `openrouter.url` pointed at `scripts/live_model.py` and grok-4.7 chose every tool call and every piece of advice. The cost column is $0.000 because nothing was billed. Token counts in the log are a rough size of the briefing, not a provider's usage report. This pass is about the choices, and about whether the helper then did the work.

| Task | Done | Time (s) | Notes |
|---|---|---|---|
| say-strip-mine | 0/1 | 1132 | 2 turns, 1 planner call. The plan was "hold 1 diamond" and `get_items` was ordered `dig`. The body then sat at y=19 digging into water behind gravel until the time limit. |
| say-wool | 0/1 | 414 | 2 turns, 1 planner call. `get_items` carried the `plains` order. The body never left a jungle tree: a walk step onto a vine over leaves timed out, over and over. |
| say-logs-stone | 0/1 | 530 | 5 turns, 1 planner call. It got 4 jungle logs, then 4 cobblestone, then came back. `finish` was refused because the pick had used the logs (1 jungle log and 30 cobblestone left). The time limit hit before the replacement logs came back. |
| say-carrying | 1/1 | 20 | One desk turn: `inventory`, "Holding nothing." No mission. |
| say-bedrock | 1/1 | 51 | One desk answer saying bedrock cannot be broken or crafted. No mission. |
| diamonds (typed) | 0/1 | 904 | 4 deaths. It was told to follow a cave down to about y=-59 and still walked peaks on the surface, then died at night. |
| sheep (typed) | 0/1 | 303 | No model call. It roamed and found no sheep. The harness stopped it at the time limit, which the result line calls "stopped by a player". |
| bedrock (typed) | 1/1 | 56 | Refused up front, with the same reason as the spoken one. |

The desk and the planner chose the right tool every time they were asked: a mission for a job, `dig` only for the strip mine, `plains` for the wool, `inventory` for the question, and a refusal for bedrock. The misses after that are the same body problems as the skills passes (vines, a dig that will not go through water, diamonds that never go underground, sheep that are not where it looks), plus one planning miss: a "done" check that asks for logs the next step is going to spend.

Next: the same eight tasks, three repeats, with a real OpenRouter key in the lab config, once the vine stall and the water-behind-gravel dig are fixed. Until then another model pass will time out in the same places.

### Diamond armor, three tries

October 21, 2026. Seed `minegoon`, practice server, a diamond-trip kit (iron pick, four stone picks, food, cobble, sticks, a shield). The sentence was "get a full set of diamond armor". There was no OpenRouter key, so the lab called `scripts/armor_model.py`. The planner name is the big model. The desk and the advisor are the small one. Each answer is a `model fire` line in the server log and a `model_fire` line in `results.jsonl` (size, role, asked model, answered model, what it did, tokens, cost, time). The stand-in never orders strip mining. The body stairs toward y=-59 when no cave in sight is deeper.

| Try | Done | Time | Pieces at the end | Model fires |
|---|---|---|---|---|
| 1 | yes | 6545 s | helmet, chestplate, leggings, boots | 1 big (planner `write_plan`), 7 small (desk `start_mission`, four `get_items`, one advice `lesson`, desk `finish`) |
| 2 | no | 7220 s, stopped at the limit | none (helmet and chestplate were made, then lost) | 1 big (`write_plan`), 5 small (`start_mission`, three `get_items`, one advice `lesson`) |
| 3 | no | 7204 s, stopped at the limit | none (helmet and chestplate were made, then lost) | 1 big (`write_plan`), 4 small (`start_mission`, three `get_items`) |

Try 1 is the one that finished. The big model wrote the plan once, at the start. The small desk ordered each piece, and called `finish` only after all four were in hand. One small advice call fired while a search was dry, and told it to stay near y=-59.

Tries 2 and 3 made the helmet and the chestplate, then left diamond level (sticks for more stone picks, or iron for a worn iron pick). Try 2 suffocated in a wall on the way up, then died to a zombie, a creeper, and a drowned while walking back for the drops, and the set was gone. Try 3 died to an enderman and then a creeper on the iron trip, and the set was gone. A death respawns at world spawn, and the drops are too far to reach before they despawn once the walk back also dies.

### Diamond armor from empty hands

October 22, 2026. Same seed and sentence, but no kit: `scripts/armor_runs.py` regenerates the world from the seed before each try (`FRESH=1`), clears the helper, and asks. The helper has to work out what the trip needs and fetch it. The goal is under 40 minutes with no deaths.

What changed in the body:

- **No digging for ore.** Ore searches walk through open ground only: cave mouths, ravine and pit bottoms, cave floors in sight, and ways down seen earlier in the search (`Prospector.knownOpenings`). Stairs and tunnels happen only when a player asks for digging. Iron is looked for in caves and cliffs, not on peaks, and stone in caves too.
- **Packing for the whole task up front.** A trip supply marked `^` in `knowledge.yml` is only found up top (meat, logs). Before the first trip it is packed for every trip the task will make. Raw meat counts, since it is cooked below. Once packing is done, the goal is planned again from what is held. Otherwise the tool chain packing made on the way (table, wooden pickaxe, furnace) gets made a second time.
- **Upgrades.** A better pickaxe, sword, or shield is made first whenever what is held makes one with no trip. The plan's own materials are not spent on it.
- **Night.** Without armor, nightfall up top is a reason to stop. With a weapon and some food, when the rest of the job is in the caves, it drops the meat still to hunt and heads down. Otherwise it uses a bed it has or can make, or digs in until morning.
- **Mobs.** `Bestiary` knows every mob's health, damage, and tactic. Each fight weighs the whole group against the helper's weapon, armor, health, and shield, and backs off or pillars up when the group is too much. Endermen are never looked at. These were added in this round:
  - A fight is dropped when another creeper closes in.
  - A creeper after the helper is backed away from before its fuse starts, not only once it hisses.
  - A getaway steers away from every creeper near, including ones the fight left out.
- **Fixes found in the tries:**
  - Swords no longer break leaves. Paper calls any tool "preferred" on a block that needs none, so both swords wore out on a jungle canopy, leaving the helper unarmed at night.
  - A meal hunt no longer stalls waiting to heal.
  - A route no longer cuts the vines it is about to climb.
  - A cell whose step failed twice is planned around.
  - Animals within 32 blocks count as seen even behind trees.
  - A merged hunt for several animals ranks biomes by how many of them live there. The jungle at spawn holds only chickens, and a forest with all four is 64 blocks east.
- **Death log.** Each death logs its cause and the mobs within 16 blocks.

What the tries showed. Times are seconds since the ask.

| Try | Furthest | Deaths | What held it up |
|---|---|---|---|
| 12 | meat hunt, night 1 | 0 | Both swords wore out on leaves, so it dug in at nightfall as if unarmed. |
| 13 | meat hunt, 3 of 6 at nightfall (585 s) | 0 | Dug in for the whole night next to the cows it had just hunted. |
| 15 | cave at y=54, night 1 | 0 | The hunt found no animals after 8 places and gave up. With no food it dug in. |
| 16 | heading down at night 1 | 1, creeper | The cook step failed on half the meat, the replan packed again, and it hunted in the dark. |
| 17 | iron search, 2000 s | 1, creeper | Reached a stone pickaxe and the iron search by 700 s. It found no way down deeper than y=63 in 600 blocks of walking, and died in the second night while backing off from a group. |
| 19 | iron search, 2100 s | 2, zombie then creeper | With the sight checks shared out between heights it walked to cave mouths, but every one in sight was shallow (y=63 to 79). Night 1 cost two minutes of backing off one creeper 33 times. In the second night a zombie killed it mid-fight, then a creeper killed it just after respawning with nothing to fight with. |

The surface now takes about 10 minutes: logs, a wooden pickaxe, cobblestone from a cave mouth, swords, a stone pickaxe, and some meat. What is still missing is a way down. On this seed the openings in sight near spawn are shallow, so the iron search walks the surface for 20 minutes or more. That runs into a second night with no armor, which is where every death since try 16 happened.

### Heading for mountains, not the water

October 24, 2026. Watching a diamond run with the chase camera, the helper walked toward the sea instead of the mountains in view. Three things in `Prospector` caused it:

- **The walk to new ground favoured diagonals.** Each compass direction counted unseen chunks along a strip, and a diagonal strip reaches about 40% further, so a diagonal had roughly 20 more unseen chunks before anything was walked. The mountain and water lean was a bonus of at most 30 and usually 5 to 15, so it rarely decided anything. Now every direction counts the same eighth of a circle, and each unseen chunk is multiplied by what the land that way looks like: 0 for water, 1 for land, 3 for mountain on a deep search.
- **"Deepest first" meant "lowest first".** The cave move walked to the lowest opening in sight, however far. On the surface the lowest dry ground with a wall beside it is a river bank or the coast. Openings and peaks are now ranked by height gained minus a quarter of the walk to them, so a way down 10 blocks deep 20 blocks off beats one 15 deep 200 blocks off.
- **Only roaming skipped shores.** Cliffs, cave openings and peaks on a shore are now skipped too. A search that roams a waterside biome (water, sand, clay, sugar cane, gravel) no longer skips shores or leans away from water. Before, roaming for water skipped every river patch, since a river patch is water.

Not run on the seed yet. The next armor try should show whether the iron and diamond searches reach a deep way down sooner.

### Running from mobs instead of fighting them

October 24, 2026. The helper still died to mobs far too often, and every death in the armor tries came from a fight it chose: a zombie killed it mid-fight, creepers killed it while it backed off a group. A fight spends health the trip needs and wins nothing, since mob drops are not on any plan. So `Fighter.defend` now runs first:

- **Run.** Anything after the helper is run from: it sprints away from the group, steering clear of creepers, until nothing is after it within 14 blocks or it has gone 24. It neither eats nor swings on the way, since both slow the sprint. A sprinting player outpaces zombies, skeletons, creepers, spiders and most other mobs.
- **Fight only what keeps up.** A mob still in reach after two seconds of running (a baby zombie, an angry enderman, a phantom) is fought, and so is the most urgent one when there is no way to run. If that group is too much and is all melee mobs that cannot climb, it pillars up instead, as before.
- **Fight what keeps coming back.** A group it has run from twice already, and would beat, is fought. Without this one zombie between the helper and the work would make it run forever, like the creeper it backed off 33 times in try 19.

The planner prompt and the `fight` tool now say the helper runs from monsters, so plans route around them instead of sending it to kill them. Not run on the seed yet (no Docker on the agent machine).
