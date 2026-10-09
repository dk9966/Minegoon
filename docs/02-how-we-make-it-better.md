# How We Make It Better

This builds on `01-sota-research.md`. Every design choice below points back to a specific project that failed without it, or succeeded because of it.

---

## The short story

Think of the agent as a small construction company instead of one very smart person.

Today's best agent (Mine AI MCP, the one that beat the dragon) is like one brilliant foreman who does everything himself, talks through every step out loud, and rereads his entire diary before every move. It works, but it's slow, expensive, and it only works with the biggest models. If he crashes, he has to work out from his diary where he was.

We split the work the way a real crew does:

- **The body** is the worker on site. He has good hands (careful actions that report honestly what happened) and good instincts (he eats when hungry and dodges creepers without being told).
- **The job board** is a whiteboard listing every task, what "done" looks like, and who is on it. It's saved to disk, so a crash, a death, or a restart never loses the plan.
- **The planner** is the architect. Smart and expensive, so we only call him when we need a new plan or when something has gone badly wrong.
- **The foreman** is fast and cheap. He takes the next job off the board and drives the worker through it, step by step.
- **The notebook** is the site logbook. Where the portal is, what's in each chest, which rooms of the mansion are cleared. The planner and foreman get a short briefing from it, not the whole history.
- **The inspector** checks every finished job against the game itself. Is the item really in the inventory? Is the wall really there? The model saying "done" never counts.
- **The blueprint office** handles building. It designs with shapes (walls, towers, roofs), shows you a preview, works out exactly what materials are needed, and hands the build to the crew in sections.
- **You** are the client. You give orders in in-game chat, approve designs, and can pause or redirect at any time.

Run like this, a dragon kill becomes a checklist of about 15 jobs, each with a clear finish line. A castle becomes "gather 4,000 stone bricks, then build the north wall layer by layer, then check it against the blueprint." A mansion becomes "find it, gear up, then clear and light each room, marking it on the map."

---

## How the pieces fit

```mermaid
flowchart TB
  you[You: in-game chat and dashboard]
  planner[Planner: smart model, called rarely]
  board[(Job board: saved to disk)]
  foreman[Foreman: fast model or playbook code]
  inspector[Inspector: checks the game, not the model]
  notebook[(Notebook: world memory in SQLite)]
  blueprint[Blueprint office: design, preview, materials]
  body[Body: Mineflayer bot with honest actions and reflexes]
  mc[Minecraft 26.3 server]

  you --> planner
  you --> board
  planner --> board
  blueprint --> board
  board --> foreman
  notebook --> foreman
  foreman --> body
  body --> mc
  body --> notebook
  body --> inspector
  inspector --> board
```

One mission goes like this:

1. You say "kill the ender dragon."
2. The planner writes a job tree with a finish line on each job (for example, "3 blaze rods in inventory").
3. The foreman takes the next open job and gets a short briefing: the job, the relevant facts from the notebook, recent events, and which playbooks are available.
4. The foreman runs a tested playbook if one fits. If not, it picks actions one at a time.
5. The body carries them out and returns a receipt for each action, describing what was actually observed.
6. The inspector checks the job's finish line against the game.
7. If it passed, the job is marked done and we move on. If it failed, we retry a different way. After a few failures, the planner is called in to re-plan.

---

## Zoom in: each piece

### 1. The body: honest hands and instincts

**What it is:** A single process connected to Minecraft through Mineflayer. It offers a small, fixed set of actions over MCP (the standard way AI models call tools): look around, walk to, collect, craft, smelt, place, fight, use container, and so on.

**Why:** Mine AI MCP won mostly because of this layer. They forked Mineflayer and its physics engine to fix cases where the bot reported a block placed or an item crafted when it wasn't. Agents built on dishonest actions, like Mindcraft's generated code, fail in ways the model can't notice.

**What we do:**
- **Start from Mine AI MCP's body** (MIT licensed) rather than writing our own. That includes their Mineflayer fixes.
- **Every action returns a receipt:** what we asked for, what was observed, and whether the observation confirmed it. For example: "placed oak_planks at (10, 64, 3): confirmed by block update."
- **Long actions run in the background.** Smelting a stack takes over 10 minutes. The foreman checks progress instead of waiting.
- **Reflexes run without the model:** eat when hungry, fight or flee from hostile mobs, escape fire and lava, surface for air, recover footing. (Mine AI MCP and mc-agents both found this essential.)
- **Protected zones (new).** Mine AI MCP's own limitations doc says the bot will dig through your walls when pathing. We register every blueprint area and every base as a protected zone in the notebook, and the pathfinder treats blocks inside those zones as unbreakable. Mineflayer's pathfinder already supports a "can't break these" list, so this is a small change with a big payoff.
- **Survival-only by design.** The bot is never given operator rights on the real server. It *can't* use `/give`, `/setblock`, or `/tp`, so it can't cheat even by accident. This is enforced by the server, not by a promise in a prompt.

---

### 2. The job board: a plan that survives anything

**What it is:** A tree of jobs saved in SQLite. Each job has a goal, a finish line the game can confirm, a status (open, active, done, failed, blocked), its attempts, and the evidence collected.

**Why:** No existing agent keeps a durable plan. Mindcraft forgets its mission on restart. Mine AI MCP's plan lives inside the model's conversation. For "build a castle overnight," a crash at 3am has to cost you nothing.

**What we do:**
- **Every job has a "done when" check the game can answer.** "Have 3 blaze_rod." "Standing in the_end." "Blueprint section `north_wall` is at least 98% matched." "Advancement `minecraft:end/kill_dragon` granted."
- **Restart means resume.** On startup, the brain reads the board, re-checks the active job's finish line, and continues.
- **Deaths become jobs.** When the bot dies, the reflex logs where it happened, and the board gets a "go back and pick up dropped items within 5 minutes" job ahead of everything else.
- **Retry with a reason.** A failed job records why it failed. The next attempt has to try something different (another route, another target, gearing up first). After 3 failures, the planner gets involved.
- **Multiple bots later for free.** Bots "claim" jobs from the board. Adding a second worker means letting a second body claim jobs, not rewriting the brain.

---

### 3. Planner and foreman: two speeds of thinking

**What it is:** Two model roles. The planner is a top model (for example Claude Opus 5 or GPT-6 Astra) that writes and repairs the job tree. The foreman is a fast, cheap model, or plain playbook code, that runs one job at a time.

**Why:** Astra + Jev killed the dragon with 35 planner calls and 131 small decisions for about $0.97. Mine AI MCP used one huge model for everything at about $93. Most moment-to-moment choices ("open this chest," "attack this zombie") don't need the smartest model.

**What we do:**
- The planner is called only at the start of a mission, when a job fails 3 times, or when you change the goal.
- The foreman handles the step-by-step loop.
- **A fresh short briefing for each job.** 88% of Mine AI MCP's cost was the model rereading its own growing history. Instead, every job starts with a briefing of a few thousand tokens built from the notebook and the board. Conversations never grow without limit.
- **Model-neutral.** One small adapter per provider, so we can swap models as better ones come out.

---

### 4. Playbooks: tested routines instead of improvised code

**What it is:** TypeScript routines for things that come up again and again: "get a full set of iron armor from scratch," "build a nether portal with a bucket and lava," "triangulate the stronghold," "destroy end crystals."

**Why:** Voyager showed that saved skills compound over time. Odyssey showed that good skills matter more than a bigger model. But Voyager let the model save any code that seemed to work, and that piles up bad skills.

**What we do:**
- Playbooks are written by us (or proposed by the model) and **only saved once they pass tests in the lab** (see section 9).
- Each playbook declares what it needs ("iron pickaxe, 1 bucket"), what it produces ("obsidian portal lit"), and its finish-line check.
- The foreman prefers a playbook. When none fits, it improvises with single actions. Improvisations that keep working become candidates for new playbooks.

---

### 5. The notebook: memory that lives on disk

**What it is:** SQLite tables for everything the bot learns about its world: explored chunks and biomes (the "frontier"), structures found (villages, fortresses, the stronghold, mansions), portals, chests and what's in them, bases, protected zones, deaths, and short notes.

**Why:** Mine AI MCP's database is what let it remember a stronghold 1,700 blocks away and a nether portal through a death. Agents without one rediscover the world over and over.

**What we do:**
- Start from Mine AI MCP's schema and add tables for structures, protected zones, mansion room maps, and build progress.
- Briefings are built by querying the notebook for what's relevant to the current job, never by dumping all of it.

---

### 6. The inspector: trust the game, not the model

**What it is:** A small checker that answers each job's "done when" question using only game facts: inventory counts, block scans, advancements, entity lists, and the bot's position and dimension.

**Why:** Voyager's results dropped a lot without its self-check. JARVIS-1 lost 10 to 20 points without its self-check (per the CVPR 2026 comparison). Astra + Jev's victory file demands proof of dragon death plus the exit portal. Models confidently claim success they didn't achieve.

**What we do:** A job can only be marked done by the inspector. The model can *ask* for a check, but it can't declare one.

---

### 7. The blueprint office: castles built the legit way

This is where we can lead the field. Nobody has published an agent that gathers materials and builds a large castle legitimately in survival.

**Step 1: Design with shapes, not block lists.** minecraft-agentic found that when a model listed blocks one by one, it produced 74 blocks with holes (3/10). With shape tools, it produced 2,773 blocks with none missing (10/10). Our design language has a small set of shapes: `wall`, `tower`, `roof` (gable, hip, cone), `battlements`, `window_row`, `arch`, `stairs`, `floor`, `room`. The model (or you) composes a castle from them, and we can also offer ready-made castle parts with adjustable size and materials.

**Step 2: Compile and preview.** The design is turned into a voxel grid, rendered as a preview you can spin around in the dashboard, and exported as a `.litematic` file you can load in Litematica to see a ghost of it in your world. **You approve the design before a single block is mined.**

**Step 3: Bill of materials.** Count every block, then work backwards through the recipes: stone bricks come from stone, which comes from smelting cobblestone, which needs fuel. The result is a gathering plan, for example "about 6,000 cobblestone, 900 oak logs, 2 stacks of coal," turned into jobs.

**Step 4: Gather and stock the site.** The bot gathers and stores materials in labeled chests next to the build site, so restocking is a short walk instead of a long trip.

**Step 5: Build in sections and layers.** The blueprint is cut into sections (north wall, tower 1, gatehouse), and each section is a job. Inside a section, we build layer by layer from the bottom up, so every block has something to rest on (the same idea as Baritone's `buildInLayers`). Temporary scaffolding gets placed where needed and removed afterward. The whole site is a protected zone, so the bot never digs through its own work.

**Step 6: Compare and repair.** After each section, the inspector scans the area and compares it to the blueprint block by block. Wrong or missing blocks become a repair list. A section counts as done at 98% or better.

**Rough scale, so expectations are right:** a serious castle is 3,000 to 20,000 blocks. Placing alone is hours. Gathering and smelting the stone is hours more. This is an overnight or multi-session job, which is exactly why the job board has to survive restarts.

---

### 8. Mission playbooks for your three goals

#### Kill the Ender Dragon

Based on the route Mine AI MCP proved. Each line is a job with its finish line:

| Stage | Done when |
|---|---|
| Wood, stone tools, food | stone pickaxe and 10+ cooked food in inventory |
| Iron gear | iron pickaxe, sword, and full iron armor |
| Diamonds | diamond pickaxe and sword (armor if found) |
| Bucket, flint and steel, nether portal | bot standing in `the_nether` |
| Find a nether fortress | fortress location saved in the notebook |
| Blaze rods | 7+ blaze rods |
| Ender pearls (endermen or piglin bartering) | 12+ ender pearls |
| Eyes of ender | 12+ eyes of ender |
| Find the stronghold | location worked out from 2+ throws and saved |
| Prepare for the End | bow, 64+ arrows, building blocks, water bucket, food, armor |
| Enter the End | bot in `the_end` |
| Destroy end crystals | no end crystals left |
| Kill the dragon | advancement `end/kill_dragon` granted |

The fight itself is a lab-tested playbook: shoot the exposed crystals, pillar up to the caged ones, then hit the dragon when it perches. Death policy: get your items back first, and never fight in the End without a full kit.

#### Clear a woodland mansion

No public agent has done this, so we'll have to be honest about difficulty and test hard.

- **Find it.** The legit shortcut is buying a woodland explorer map from a cartographer villager. Otherwise, search dark forests using the notebook's frontier map. Mansions are usually very far from spawn, so plan the trip like a mission (food, beds, a waypoint base).
- **Gear up.** Full iron or diamond armor, a shield, a bow and arrows, plenty of food, torches, and building blocks.
- **Clear room by room.** As the bot explores, it records each room and doorway in the notebook. Each room is a job: enter, clear, light it up so nothing respawns, mark it cleared.
- **Fight smart, not fair.** Group fights are where today's agents are weakest (Mine AI MCP's combat handles one target at a time). We add tactics: fight from doorways so only one enemy reaches you at a time, kill evokers first (they summon vexes that fly through walls), back off and heal below a health threshold, and use the bow from range.
- **Done when:** every room in the mansion map is marked cleared and lit, and loot is stored in a chest at base.

#### Build a castle

The blueprint office pipeline above: design, approve, gather, build in sections, compare, repair.

---

### 9. The lab: test before trusting

**What it is:** A separate test server where we're allowed to cheat during *setup*: give items, spawn mobs, build rooms. Then we run one playbook many times and measure how often it succeeds.

**Why:** Mine AI MCP has exactly one successful run, so we don't know its success rate. Astra + Jev uses a combat lab for this reason. "It worked once" isn't reliability.

**What we do:**
- Scenario scripts, for example: "iron armor, sword, and shield; three vindicators in a 7x7 room; run 20 times; count survivals and kills."
- A playbook is trusted once it hits a target success rate, for example 90% for basic jobs and 70% for dangerous fights to start.
- We track a few numbers over time: time to iron, time to diamonds, time to blaze rods, time to dragon, deaths per hour, model cost per milestone, and blueprint match percentage.
- Construction tests reuse MineCollab blueprints and MineCEraft tasks where we can.
- How to set up runs, measure them, and read the logs is written out in `04-testing-dialogue.md`.

---

### 10. You stay in charge

**Why:** Steve AI's best idea was the experience: press a key, type what you want, watch it happen.

**What we do:**
- **In-game chat commands.** `!mission kill the dragon`, `!status`, `!pause`, `!resume`, `!come`, `!approve`, `!stop`. Short and always available.
- **A web dashboard (client)** with a live 3D view of the bot (Mineflayer's prismarine-viewer), the job board, the notebook map, cost so far, and buttons to approve designs or pause.
- **Approval gates** for anything big or irreversible: starting a castle, leaving for a distant mansion, entering the End.

---

## Why this design (and not something fancier)

Every piece exists because a real project showed we need it:

| Piece | Problem it prevents | Evidence |
|---|---|---|
| Honest actions with receipts | Model acts on false "success" | Mine AI MCP had to fork Mineflayer to fix false reports |
| Reflexes | Dying while the model thinks | Mine AI MCP and mc-agents |
| Protected zones | Bot digging through its own castle | Mine AI MCP limitations doc |
| No operator rights | Accidental cheating | Steve AI and minecraft-agentic both build with free blocks |
| Job board on disk | Losing hours of progress on a crash | No existing agent has one |
| Planner and foreman split | Cost and slowness | $0.97 (Astra + Jev) vs about $93 (Mine AI MCP) |
| Short briefings | Context rereading cost | 88% of Mine AI MCP's cost |
| Tested playbooks | Piles of bad self-written skills | Voyager vs Odyssey |
| Inspector | Confident false "done" | Voyager and JARVIS-1 lose a lot without self-checks |
| Shapes for design | Lazy, holey builds | 74 blocks (3/10) vs 2,773 blocks (10/10) |
| Lab | "It worked once" | One public dragon run, unknown success rate |

**What we deliberately leave out:**
- **Training our own vision model.** The "eyes and fingers" research agents are still at about 15% on diamond-level tasks. The brain + body approach is far ahead for real use.
- **A crew of bots from day one.** MineCollab found that more agents often made things worse because they communicate badly. The job board makes adding bots easy later, once one bot is solid.
- **Letting the model write and run any code it wants.** Flexible, but it's where Mindcraft-style agents break. Improvisation is allowed through fixed actions, and new code only joins the playbook set after it passes lab tests.

---

## Roadmap

Each phase has an exit test. Update the status and notes as phases finish, so the next phase starts with an accurate picture.

| Phase | What | Exit test | Status |
|---|---|---|---|
| 0 | Research and planning docs | These three docs written | Done (Oct 8, 2026) |
| 1 | World and body hello | Local 26.3 server running; you and the bot both in the world; bot walks to you on `!come` | Not started |
| 2 | Body tools and notebook | Mine AI MCP's body ported and running against 26.3 through the translator; receipts and SQLite memory working; protected zones in place | Not started |
| 3 | Brain loop | Job board, inspector, planner, foreman. First mission: "full iron armor from a fresh spawn," which passes 8 out of 10 lab runs | Not started |
| 4 | Dragon | Full dragon mission on normal difficulty, logged from start to finish; then a success-rate measurement over several seeds | Not started |
| 5 | Castle | Blueprint office end to end: design, preview, approve, gather, build in sections, at least 98% blueprint match, all in survival | Not started |
| 6 | Mansion | Combat tactics lab-tested; one full mansion cleared and lit | Not started |
| 7 | Crew | Two or more bots sharing the job board on one castle | Not started |

### Notes for the next phase

- Phase 1 setup is in `03-deployment-strategy.md`.
- Before porting Mine AI MCP's body, check whether their pinned Mineflayer fork works through ViaBackwards on 26.3, or needs rebasing onto upstream Mineflayer 4.39.0 (which natively supports up to 26.1).
- Some of Mine AI MCP's development tools (Mine Labs) need private GitHub access. Plan to build our own lab scenarios instead of relying on theirs.
- The testing method (same seeds, one setting at a time, harness-judged finish lines, read the first mistake in the logs) is recorded in `04-testing-dialogue.md`. Use it when Phase 3's lab starts.
