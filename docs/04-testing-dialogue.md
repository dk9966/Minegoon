# Testing Dialogue

Recorded October 8–9, 2026. This is the conversation about how to test the agent, kept as it was said, so later phases can look it up. It expands section 9 of `02-how-we-make-it-better.md` (the lab).

---

## 1. What is the correct way to test how well the agent does on a task in different settings?

**Asked:** October 8, 2026, 5:31 PM.

The right way is to treat it like a fair science experiment. Pick one task with a clear finish line, change one setting at a time, run each setting many times from the same starting points, and compare success rates while being honest about how much luck is involved. The lab plan in `02-how-we-make-it-better.md` (section 9) already points this way. Below is the full version.

### The plain-English story

Say you want to know whether a cheap model can get full iron armor as well as an expensive one.

You can't run each once and pick the winner. Agents are random: the same agent can win on Monday and die to a creeper on Tuesday. One run tells you almost nothing.

So you set up a race track. You fix the starting conditions: the same world seeds, the same starting items, the same spawn point. You decide ahead of time what counts as "finished" (full iron armor in the inventory, checked by the game, not by the model saying so). You also set a time limit.

Then you send each version of the agent around that same track many times, on the same set of seeds. The only difference between the two groups is the one thing you're testing, which here is the model. Everything else stays identical.

At the end you count wins, but you also look at how long each run took, what it cost, and how often the bot died. Most importantly, you read the failures. "8 out of 10" is a score. "Both failures were the bot falling into lava while mining" is something you can fix.

Last, you stay humble about small numbers. 8 out of 10 versus 7 out of 10 is basically a tie. You need a lot of runs before a small gap means anything.

### Zoom in

**1. Define the task and finish line before you start.** Write down what the task is, where the bot starts, what counts as success, and the time or step limit. The inspector idea fits here exactly: success is a fact the game can confirm, like an item count, an advancement, or a blueprint match percentage. If you decide what "success" means after seeing results, you'll fool yourself without meaning to.

**2. Change one thing at a time.** A "setting" is any knob you might turn: which model, playbooks on or off, difficulty, reflexes on or off, how long the briefing is. If you change two knobs at once and the score goes up, you can't tell which one did it. Keep a baseline (the current best version), and each experiment is "baseline plus one change."

**3. Use the same starting points for every setting.** Make a fixed list of world seeds and starting setups, say 20, and run every setting on all of them. This is a paired comparison. If seed 7 puts the bot next to a ravine, every setting gets that ravine. That removes a lot of luck from the comparison, so you need fewer runs to see a real difference.

Things that quietly vary between runs and should be held steady:

- Model temperature and the exact model version
- Server tick rate and lag (run on the same machine, and don't run so many in parallel that the server slows down)
- In-game time of day and weather at the start
- Mod and plugin versions

**4. Run enough times, and know what "enough" means.** Rough rule of thumb for success rates:

- With 10 runs, your measured rate is off by about plus or minus 25 percentage points.
- With 20 runs, about plus or minus 18.
- With 50 runs, about plus or minus 11.

So 20 runs is enough to tell "works most of the time" from "mostly fails," but not 80% from 90%. For a fine-grained comparison, you need around 50 or more per setting. Report results as "16/20 (80%, likely somewhere between 58% and 92%)" instead of a bare 80%. That range is called a confidence interval; Wilson intervals are the usual way to compute it for small counts.

**5. Measure more than pass or fail.** For each run, record:

- Success or failure, and which stage it reached (it got iron but not armor)
- Time taken (game time and real time)
- Model cost and number of model calls
- Deaths, retries, and how many times the planner had to re-plan
- The full log of actions and receipts

The stage reached matters a lot for long missions like the dragon. Two settings might both fail every time, but one consistently gets to the Nether and the other dies before diamonds.

**6. Read the failures and sort them.** After a batch, go through every failed run and give it a cause: lava death, pathfinding stuck, wrong craft, model gave up, timeout. Count the causes. That tells you what to fix next, which is usually worth more than the score itself.

**7. Don't grade on the same tests you tuned on.** If you keep tweaking a playbook until it passes your 20 seeds, it may just be memorizing those seeds. Keep a second set of seeds you never look at while tuning, and only run it to confirm a change before you trust it. This is the "held-out set."

**8. Make setup allowed to cheat, but the run not.** The lab can `/give` items, spawn mobs, and build arenas to create the starting state, but once the run starts the bot plays legitimately. This lets you test a single stage (for example "starting with iron gear, get blaze rods") without replaying the whole game each time.

### What a single experiment looks like

| Setting | Seeds | Runs | Success | Median time | Avg cost |
|---|---|---|---|---|---|
| Baseline (Opus planner, playbooks on) | A1 to A20 | 20 | 17/20 | 14 min | $0.40 |
| Same, but playbooks off | A1 to A20 | 20 | 9/20 | 22 min | $1.10 |

Same task, same seeds, one change. The gap is big enough to trust even with 20 runs each, and the failure log tells you why.

---

## 2. Specifically, how to set up the runs and measure the runs

**Asked:** October 8, 2026, 5:33 PM.

You build a small test harness (a separate program, say `packages/lab`) that runs the bot through a scenario over and over, resets the world between runs, judges each run itself, and writes one result line per run to a file. A second small script reads that file and prints the comparison.

### The plain-English story

You write the test down once as a scenario file: "start in this world, with these items, at this spot; the goal is full iron armor; you have 20 minutes." You also write a short list of the settings you want to compare, such as "Opus planner" versus "cheap planner."

Then you press go. For each setting, for each seed, for each repeat, the harness does the same six steps:

1. Gives the bot a fresh world.
2. Uses admin commands to set up the exact start (clear inventory, give items, teleport, set the time to morning).
3. Starts the agent with that run's setting and hands it the goal.
4. Starts a stopwatch and watches.
5. Ends the run when the goal is met, the bot hits a give-up condition, or the time runs out. The harness checks the goal itself by asking the server, not by trusting the agent.
6. Writes one line to a results file: what was tested, whether it passed, how far it got, how long it took, what it cost, how many times it died, and where the full log is.

When the batch finishes, a summary script groups those lines by setting and prints success rates with error ranges, median time, average cost, and the most common failure reasons.

### Zoom in

**1. The scenario file: what the test is.** One file per test, written before any runs. Something like `lab/scenarios/iron-armor.yaml`:

```yaml
name: iron-armor-from-spawn
goal: "Get a full set of iron armor"
seeds: [1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010]
repeats: 2
time_limit_minutes: 20
setup:
  - "clear Builder"
  - "time set day"
  - "weather clear"
  - "kill @e[type=!player]"
success:
  inventory:
    iron_helmet: 1
    iron_chestplate: 1
    iron_leggings: 1
    iron_boots: 1
stages:
  - { name: wood, inventory: { oak_log: 4 } }
  - { name: stone_pickaxe, inventory: { stone_pickaxe: 1 } }
  - { name: iron_ore, inventory: { raw_iron: 3 } }
  - { name: iron_pickaxe, inventory: { iron_pickaxe: 1 } }
```

The `stages` list is what gives you "how far did it get" on failed runs. For a later-stage test like blaze rods, the `setup` section would also `give` the bot iron gear and `tp` it into the Nether, so you don't replay the early game every time.

**2. The settings file: what you're comparing.**

```yaml
baseline:      { planner: opus-5, foreman: fast-model, playbooks: on }
no-playbooks:  { planner: opus-5, foreman: fast-model, playbooks: off }
cheap-planner: { planner: fast-model, foreman: fast-model, playbooks: on }
```

Each one differs from `baseline` by exactly one setting. The brain reads its configuration from this, so nothing about the agent is hard-coded per test.

**3. The run loop: how one run actually happens.**

*Fresh world.* Two options:

- *Clean but slow:* stop the lab server container, delete the world folder, set the seed (the itzg image takes a `SEED` setting), and start it again. That's roughly 30 to 60 seconds per run. Use this for "from spawn" tests.
- *Fast:* generate each seed's world once, save a copy of the folder as a template, and copy the template back in before each run. The terrain is identical every time, and resetting takes a few seconds.

Use the template approach. It's fast and keeps the terrain the same across settings.

*Setup through the server console, not the bot.* The harness connects to the lab server over RCON (the remote admin console the itzg image enables by default) and runs the `setup` commands. The bot itself is never made operator, so it still can't cheat during the run.

*Start the agent.* Launch the body and brain as a fresh process with the setting's configuration, a fresh empty notebook and job board (otherwise run 2 remembers run 1), and the goal text. Give every run its own folder for logs.

*Watch and judge.* Every few seconds the harness checks the success and stage conditions itself, either over RCON or through a separate read-only observer bot. The agent's own inspector can't end the run, because then you'd be grading the agent with its own answer key. The harness also records deaths from server events.

*End conditions:* success, time limit reached, the agent declared it was stuck, or the process crashed. Record which one happened. "Timed out" and "crashed" are very different problems.

*Teardown:* kill the agent process and save its logs. The next reset wipes the world.

**4. What each run records.** One JSON line per run, appended to `lab/results/<experiment>/runs.jsonl`:

```json
{"scenario":"iron-armor-from-spawn","setting":"baseline","seed":1003,"repeat":1,
 "outcome":"timeout","stage_reached":"iron_ore","success":false,
 "game_seconds":1200,"wall_seconds":1240,
 "planner_calls":4,"foreman_calls":212,"input_tokens":381000,"output_tokens":22000,"cost_usd":0.61,
 "deaths":2,"replans":1,"failure_note":"","log_dir":"runs/0042",
 "git_commit":"a1b2c3d","model_versions":{"planner":"opus-5-2026-09","foreman":"..."}}
```

Some notes:

- **Cost and token counts** come from the brain logging each model call's usage, which the provider returns with every response.
- **`git_commit` and model versions** let you tell later whether a score change came from your code or from the provider updating its model.
- **`failure_note`** starts empty. You fill it in after reading the log ("fell in lava," "stuck in pathfinding," "crafted the wrong item"). Do this by hand at first. Once the same few causes keep showing up, add automatic tags for them.

One line per run in a plain file means you can re-summarize anytime, merge batches, and never lose old results.

**5. The summary: how you measure.** A short script reads `runs.jsonl` and prints, for each setting:

- **Success rate with a range**, for example `16/20 = 80% (58% to 92%)`. The range is a Wilson interval, which is the standard way to show how much a small-sample success rate could be luck. If two settings' ranges overlap heavily, you haven't shown a difference yet.
- **A per-seed comparison against baseline:** on how many seeds did the new setting win where baseline lost, and the other way around. Every setting ran on the same seeds, so this is the fairest head-to-head.
- **How far it got:** how many runs reached each stage. This is the useful number when everything fails.
- **Median time and average cost, counting successful runs only.** Failed runs hit the time limit and would drag the numbers toward 20 minutes.
- **Failure causes:** a count of each `failure_note`.

**6. Practical points that bite.**

- **Minecraft isn't fully repeatable even with a fixed seed.** Mob spawns and drops are random. That's fine: it's why you repeat runs, and the fixed seed still keeps the terrain fair.
- **Run one test at a time at first.** Running several in parallel on one machine can slow the server, and a lagging server makes bots fail in ways that have nothing to do with the setting. If you parallelize later, give each run its own server container, and check that the server is keeping up at full speed (20 ticks per second, which the server's `tick query` command reports).
- **Keep tuning seeds and checking seeds separate.** Tune on seeds 1001 to 1010. Before trusting a change, confirm it on 2001 to 2010, which you never looked at while tuning.
- **Shuffle the run order** across settings and seeds, so something like your Mac heating up over the evening doesn't always hurt the same setting.

---

## 3. Should we vet the process to learn where it's messing up, or just look at the results?

**Asked:** October 9, 2026, 9:11 PM.

Look at both, but give them different jobs. The results are the score: they tell you whether a change helped. The process (what the agent actually did, step by step) is how you find out why it failed and what to fix. Don't grade the process, and don't read every run. Read the failures, plus a few successes, and look for the first thing that went wrong.

### The plain-English story

Think of a coach watching game tape. The scoreboard says you lost 3 to 1. That tells you something is wrong, but not whether the problem is the defense, the goalkeeper, or the game plan. So the coach watches the tape of the goals against, not the whole season.

For the agent, the scoreboard is the results file and the tape is each run's log. After a batch you get a pile of results, like "11 out of 20 succeeded, most failures stalled around iron." Then you open maybe 5 to 10 of the failed runs and scroll through what happened.

The key habit is to find the first mistake, not the last one. The bot died in lava, but why was it near lava? Maybe the foreman chose to mine downward. Maybe that happened because the notebook had no record of any surface iron. Maybe that was because the planner skipped exploring. The death is where the run ended. The skipped exploring is the real cause.

You write a one-line cause for each failure, count the causes, fix the most common one, and run the batch again. The results tell you whether the fix worked.

Over time you notice the same causes repeating. Those become small automatic checks, so the harness flags them for you and you read fewer logs by hand.

### Why results alone aren't enough

Results can only tell you that something broke, never what. Looking at results alone also leads you astray in two ways:

- **You fix the wrong thing.** If iron-armor runs keep failing and you upgrade the model, success may barely move, because the real problem was a mining action that reported success when nothing was mined.
- **Successes hide problems.** A run can pass by luck (spawned next to a village chest with iron in it) or by doing something you don't want (digging through a protected wall). The results file marks both as passes.

So yes, it's worth doing. But it's a diagnosis tool, not a second score.

### Zoom in

**1. Make each run's log easy to read.** A raw dump of model messages is miserable to read. The design already produces the right pieces, so log them as one timeline per run:

- Job board changes: job started, finished, failed, and the planner's re-plans.
- Each foreman decision: what it saw in its briefing, which action or playbook it picked, and its short stated reason.
- Each body receipt: what was asked for, what was observed, and whether they matched.
- Reflex events: ate, fled, fought.
- Inspector checks: pass or fail, and the evidence.
- Every 30 seconds or so: health, position, dimension, and key inventory counts.

Then build a simple view, even just a script that prints the timeline as readable text, grouped by job. Show how long each job took and how many tries it needed. That way you can see "the bot spent 9 minutes on 'find iron' with 40 walk actions" without reading every line.

**2. Decide which runs to open.**

- **Failures:** read most of them while the agent is new. Later, read a sample, around 5 to 10 per batch.
- **A few successes:** 2 or 3 per batch, to catch lucky wins, wasted effort, and rule breaking.
- **The odd ones:** the slowest successes, the most expensive runs, and any run where one setting failed on a seed that baseline passed. The per-seed comparison tells you exactly which of these to open.

**3. Find the first mistake and blame one part.** For each failed run, scroll to the point where it first went off course and ask which part of the agent was at fault. The design breaks into parts that map well onto blame:

- **Planner:** wrong plan, missing step, or bad finish line on a job.
- **Foreman:** sensible plan, but it picked the wrong action, ignored something in its briefing, or looped.
- **Briefing or notebook:** the foreman made a reasonable choice given what it was told, but what it was told was wrong or missing.
- **Body:** the action failed or its receipt was wrong, such as a "placed" block that isn't there or pathfinding that got stuck.
- **Reflexes:** died to something a reflex should have handled.
- **Inspector:** passed or failed a job wrongly.
- **Setup or infrastructure:** server lag, a crash, or a broken scenario. Not the agent's fault, so fix the harness and drop the run.

Write that down as the `failure_note`, for example `foreman: kept mining down at y=12 instead of exploring; first at 4:10`. The part name at the front is what you count.

This is where the split design pays off. With one big model doing everything, every failure looks like "the model messed up." With separate parts and honest receipts, you can usually tell which part to fix.

**4. Turn repeated causes into automatic flags.** Once a cause shows up a few times, write a check that spots it in the log automatically:

- The same action repeated 10 or more times with no progress (a loop).
- A receipt that says success while the next observation disagrees.
- One job running longer than some limit.
- A death within a few seconds of lava, a fall, or a mob, with no reflex firing.
- Any block broken inside a protected zone.

These go into the results line as tags, so the summary can say "7 of 9 failures flagged as loops" before you open a single log. You keep reading logs to find new kinds of failure, but you stop re-diagnosing known ones by hand.

You can also have a model read a log and suggest the first mistake. That saves time on long runs, but check its answers against your own notes on a dozen runs before trusting it, because it will confidently blame the wrong part.

**5. Track a few process numbers, but don't make them the goal.** Some process numbers are cheap to compute and worth watching next to the success rate:

- Success rate per job type, such as "find iron" versus "craft armor."
- Average tries per job.
- How often receipts disagree with what was observed.
- Re-plans per run.

They point you to the weak part. But don't start judging whether the agent's reasoning looked smart, or tuning it to score well on these. An agent can look careful and still fail, and you'd end up improving how it looks instead of what it does. The success rate stays the score. The one exception is hard rules, like never breaking protected blocks and never cheating: a pass that breaks one of those counts as a failure.

**6. The loop, put together.**

1. Run the batch and read the summary. If a setting looks clearly better or worse and you understand why, you may not need logs at all.
2. If results are bad or surprising, open the failed runs and a few successes. Find the first mistake in each and note which part was at fault.
3. Count the causes and fix the most common one.
4. Rerun the same seeds. Success should go up and that cause should shrink. If it doesn't, your diagnosis was wrong, and that's worth knowing too.
5. Add an automatic flag for that cause so it's caught next time.

In short: results tell you whether a change helped, and logs tell you what to change next. Early on, while the agent is new, expect to spend most of your time in the logs. Once the common failures are covered by automatic flags, you'll spend most of your time reading results.
