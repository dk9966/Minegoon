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
- Set the time to morning, clear the weather, and clear mobs already standing around before the trip starts. Mobs that wander up during the trip are part of the trip.

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
| diamonds | 1 diamond | iron pickaxe, sword, shield, food, blocks | The deep trip: kit check, then cave and stairs down to y=-59. |
| spar-zombie, -skeleton, -spider, -creeper | win a fight | stone sword, shield | The fighter against each kind of mob, spawned 8 blocks away. |
| spar-skeleton-bow | win a fight | stone sword, shield, bow, 32 arrows | Shooting with the arrow's drop allowed for. |
| bedrock | 1 bedrock | none | Impossible: the model should refuse it up front. |
| amethyst | 1 amethyst_shard | iron pickaxe | Nothing in the planner's tables: the model has to say where it comes from. |
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
