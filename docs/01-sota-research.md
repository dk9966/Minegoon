# Minecraft AI Agents: Where Things Stand (October 2026)

Research date: October 8, 2026. Latest Minecraft Java release at the time of writing: **26.3** ("Wilderness Bound", released September 15, 2026).

---

## The short story

There are two very different ways people build Minecraft AI.

**The first way is "eyes and fingers."** You train a neural network on thousands of hours of YouTube gameplay so it learns to look at the screen and press keys like a human. OpenAI's VPT, STEVE-1, JARVIS-1, and Optimus-3 are in this family. It is impressive science, but it is still unreliable at long jobs. The best of them get a diamond pickaxe roughly 1 time in 7 or 8, and they need serious GPUs and research setups to run. Nobody in this family is close to building you a castle or killing the dragon on request.

**The second way is "a brain driving a robot body."** A language model (Claude, GPT, Gemini) does the thinking, and a code library called **Mineflayer** acts as the body. The model doesn't press keys; it says things like "walk to this iron ore and mine it," and the library handles the walking, the physics, and the network packets. Voyager, Mindcraft, Steve AI, Mine AI MCP, and the Astra + Jev agent are all in this family. This is where the real wins are. In September 2026, two separate agents in this family beat the Ender Dragon starting from an empty inventory in a fresh world.

When you line up every project, the same lesson keeps coming back: **the model is rarely the problem anymore.** Agents fail because their hands are clumsy (an action reports success when it didn't actually happen), because they forget things (where the portal was, what is in the chest), or because nobody checks their work (the model says "done" and nobody verifies it). The agent that beat the dragon won mostly through very reliable, honest tools and a database for memory, not through a clever prompt.

For your three goals, here's where things stand:

- **Kill the Ender Dragon in survival:** solved once, in public, with full logs (Mine AI MCP, about 4 hours, about $93 of model usage if billed at API rates). It can be reproduced and improved.
- **Build a really cool castle in survival:** half-solved. Agents can *design* good castles, and agents can *place* blocks, but every impressive castle demo cheats by spawning blocks out of thin air. Nobody has published an agent that gathers the materials and builds a big castle legitimately.
- **Clear a woodland mansion:** unsolved. No public agent has done it. It's the hardest of the three because it means fighting groups of strong mobs in a tight maze.

---

## Scorecard at a glance

| Project | Family | Minecraft version | Real survival? | Beat the dragon? | Maintained? |
|---|---|---|---|---|---|
| Steve AI | Brain + custom body (mod) | 1.20.1 (Forge) | No, cheats when building | No | Quiet since Jan 2026 |
| Mindcraft | Brain + Mineflayer | up to 1.21.11 | Yes | No | Yes (v0.1.4, Mar 2026) |
| Voyager | Brain + Mineflayer | 1.19 | Yes | No | No (last commit Jul 2023) |
| Mine AI MCP | Brain + Mineflayer | 1.21.4 (tested) | Yes | **Yes**, normal difficulty | Brand new (Sep 2026) |
| Astra + Jev | Brain + Mineflayer | 1.16.5 | Yes, but Peaceful | **Yes**, in 8:43 | Brand new (Sep 2026) |
| Baritone | Not AI, a pathfinding bot | **26.3** | Yes | No (no combat) | Yes (v1.20.0, Sep 2026) |
| VPT / STEVE-1 / JARVIS-1 / Optimus-3 | Eyes and fingers | Old research versions | Yes | No | Research code |

---

## Zoom in: each project

### 1. Steve AI (YuvDwi/Steve)

**What it is:** A Forge mod for Minecraft 1.20.1 that adds AI helper characters ("Steves") into your world. You press K, type "build me a castle" or "mine some iron," and the Steves go do it. About 1,400 GitHub stars. Pitched as "Cursor for Minecraft."

**How it works, plainly:** Each Steve is a custom mob living inside the game. When you type a command, an LLM turns it into a small list of actions (build, mine, attack, follow). Java code inside the mod then carries out those actions tick by tick. Several Steves can split a building into sections and work in parallel.

**Strengths:**
- The best user experience of any project. Press a key, type plain English, and watch it happen. No servers, no second account, no terminal.
- Multiple helpers can work on one build without tripping over each other (it divides the build into zones).
- Fast responses, because it's designed to use quick models (Groq) for simple commands.
- Can load saved structure files, so it can build things you designed elsewhere.

**Weaknesses:**
- **It isn't real survival.** According to the project's own technical write-up, when building, a Steve places blocks directly into the world without needing them in an inventory, teleports when it's more than 5 blocks from the next block, and turns on flying and full invincibility. Crafting is listed as "stubbed out." So the castle is free; nobody gathered anything.
- Stuck on Minecraft 1.20.1 and Forge. That's years behind 26.3.
- Short memory. It plans one command at a time and has no long-running goal like "beat the game."
- There is an open bug report (still open as of Feb 2026) saying the config file resets on every launch and that Gemini and Groq support doesn't actually work.
- The last code push was January 2026.

**What to steal:** The chat-command experience, and dividing a build into zones so multiple workers can share it.

---

### 2. Mindcraft (mindcraft-bots/mindcraft)

**What it is:** The most popular open-source LLM Minecraft agent (about 5,000 stars). Each bot is a Mineflayer player that you chat with in-game. Supports almost every model provider (OpenAI, Anthropic, Google, xAI, DeepSeek, local Ollama, and more). The team also published a research paper and a benchmark called MineCollab.

**How it works, plainly:** The bot reads chat and its surroundings, then the LLM either picks a ready-made command ("collect 10 oak logs") or writes a small JavaScript program using a library of skills. The program runs, and the result goes back to the model.

**Strengths:**
- Mature and battle-tested. Lots of contributors, a Discord, Docker support, a web control panel, text-to-speech.
- Works with nearly any model, including local ones, plus the team's own fine-tuned "Andy" models.
- Real survival. The bot has to actually mine and craft.
- Good at short, conversational jobs: "follow me," "get me some wood," "make a pickaxe."
- Has a construction benchmark with blueprints, which is useful for testing building.

**Weaknesses:**
- **Not built for long missions.** There's no durable plan that survives crashes, deaths, or restarts. Multi-hour goals like "beat the dragon" fall apart.
- Version lag. It officially supports up to 1.21.11 (recommends 1.21.6). Running it on 26.x needs a protocol translator.
- Letting the model write and run code is flexible but fragile. A small mistake in the generated code can mean a failed action or a confused bot.
- Their own research found team play is weak. On the construction benchmark, the best model at the time (Claude 3.5 Sonnet) scored 0.36 out of 1, and adding more agents often made things *worse*. The main reason was that bots communicate badly with each other.
- To join servers that check accounts, each bot needs its own real Minecraft account.

**What to steal:** The skill library, the multi-provider model setup, and the MineCollab blueprint tasks as a test suite.

---

### 3. Voyager (MineDojo/Voyager)

**What it is:** The 2023 research project (from NVIDIA and others) that started the "brain + Mineflayer" family. GPT-4 played Minecraft and taught itself new skills as it went.

**How it works, plainly:** Three ideas. (1) A "curriculum" where the model picks its own next challenge ("I have wood, now let's try stone tools"). (2) A "skill library": whenever the bot writes code that works, it saves that code under a name so it can reuse it later. (3) "Self-check": after each attempt, the model looks at the result and decides if it really worked, and if not, fixes the code and tries again.

**Strengths:**
- The skill library idea is the most-copied idea in the field, and for good reason. Skills pile up, so the bot gets better over time.
- Strong results for its time: 3.3 times more unique items and tech-tree milestones up to 15.3 times faster than earlier agents.
- Skills carried over to brand-new worlds.

**Weaknesses:**
- **Abandoned.** The last commit was July 2023, on Minecraft 1.19.
- It explores on its own and doesn't take orders. It's built to "discover things," not to "build my castle."
- Combat is weak. It never got near the dragon.
- Expensive: lots of GPT-4 calls per skill.

**What to steal:** The skill library and the self-check loop. A later paper (CVPR 2026) found that when you remove Voyager's self-check, its performance drops a lot.

---

### 4. Mine AI MCP (aibengineering/mine-ai-mcp) — the one that beat the game

**What it is:** A toolbox (an MCP server) that gives any AI client a set of 37 reliable Minecraft tools. On September 15, 2026, Claude Opus 5 used it to go from a fresh world to a dead Ender Dragon in a single unbroken session, with no human help after the first prompt. The whole run is published: transcript, database, server logs, and a video. Code is MIT licensed.

**The run, by the numbers:**
- Minecraft 1.21.4 vanilla, normal survival
- 3 hours 56 minutes, 388 tool calls, 1 death (a creeper), and it recovered its items
- Route: diamond gear, then Nether for blaze rods, then 12 ender pearls, then found the stronghold by throwing eyes of ender and working out where the lines cross, then the End: shot 8 crystals with a bow, climbed up and melee'd the 2 caged ones, then killed the dragon with a diamond sword while it was perched
- About $93 if billed at API rates (it actually ran on a subscription)

**How it works, plainly:** The tools are very careful and very honest. Every action returns a "receipt" saying what was *actually observed* in the world, not what was supposed to happen. Long actions (smelting a stack takes over 10 minutes) run in the background and the model checks on them. A built-in reflex system handles danger (hostile mobs nearby, fire, drowning, hunger) without asking the model. Everything the bot sees is saved into a local SQLite database: explored chunks, chests, inventory, events, and the bot's own notes.

**Strengths:**
- **Proven.** It's the only public, fully logged dragon kill on normal difficulty.
- Honest tools. The team even forked Mineflayer and its physics engine to fix bugs where inventory counts and block placements were reported wrong. That level of care is exactly why it worked.
- Memory lives in a database, not in the model's head, so notes survive death and restarts.
- Reflexes keep the bot alive while the model is thinking.

**Weaknesses:**
- **It is only a body, not a brain.** It deliberately has no planner. It relies on a huge-context model (1 million tokens) being the brain, so the results won't hold up with smaller models.
- **Cost comes from rereading.** 88% of the $93 was the model rereading its own ever-growing history on every turn. And 92% of the 4 hours was the agent waiting for the game: walking, mining, smelting.
- **It doesn't protect your buildings.** Their limitations doc says that when the bot walks or digs, it treats player-built walls the same as natural dirt. Very bad news for a castle.
- Tested on 1.21.4 only. Some development tools need private GitHub access.
- One successful run. Runs aren't repeatable move for move, so we don't know the success rate yet.

**What to steal:** Almost the whole body layer: the receipt idea, the reflexes, background actions, the SQLite memory, and their Mineflayer bug fixes.

---

### 5. Astra + Jev (rmalde/minecraft-agent) — the speedrunner

**What it is:** Published September 20, 2026. It killed the Ender Dragon in **8 minutes 43 seconds** from an empty inventory. It splits thinking into two levels: GPT-6 Astra as the slow, smart planner, and a small fast model called Jev that picks each next action.

**How it works, plainly:** Astra sets goals ("get blaze rods, then head to these coordinates"). Jev looks at the current state and picks one action from a short menu (move, dig, pick up, craft, open chest, eat, attack). Mineflayer carries it out. The whole run used only 35 planner calls and 131 action picks, for about $0.97 total.

**Strengths:**
- Shows how much cheaper and faster a two-level brain is: a few big-model calls plus many tiny fast decisions.
- Publishes evidence files (event logs, plus a victory file that requires proof of dragon death and the exit portal), so the claim can be checked.
- Has a separate "combat lab" for testing fights without doing a whole run.

**Weaknesses:**
- **It's a rehearsed speedrun, not general play.** Peaceful difficulty (no hostile mobs in the overworld), on a seed they had already scouted, using a tested route.
- Kills the dragon with exploding beds, a speedrunner trick that's tightly scripted.
- Uses a small helper that reads the dragon's exact head position from the server. It doesn't change the game, but a normal player wouldn't have that info.
- Minecraft 1.16.5. Relies on a hosted decision API for Jev.

**What to steal:** The planner + fast-controller split, and the "prove it with evidence files" habit.

---

### 6. mc-agents (jblemee/mc-agents)

**What it is:** A small open-source project where an LLM command-line tool (Claude Code, Gemini CLI, and others) controls Mineflayer bots.

**The one idea worth noting:** It splits "reflexes" from "strategy." A Node process that's always running eats when hungry, fights or runs from mobs, and digs an emergency shelter, all without waiting for the LLM. The LLM only handles strategy. Same lesson as Mine AI MCP, in a simpler form.

---

### 7. Baritone (cabaletta/baritone) — not AI, but the best legs in Minecraft

**What it is:** A long-running pathfinding mod (about 8,800 stars) that controls *your own* player. Type `#goto 1000 500` or `#mine diamond_ore` and it does it. It already supports **Minecraft 26.3** (v1.20.0, released September 23, 2026) on Fabric, Forge, and NeoForge.

**Strengths:**
- The most reliable and fastest pathfinding in Minecraft. It handles parkour, mining through obstacles, and even elytra flight through the Nether.
- **It can build schematics in survival using blocks from your inventory** (`#build`, `#litematica`, with settings like `allowInventory` and `buildInLayers`). That's exactly the "place a big castle legitimately" piece most AI agents are missing.
- Keeps up with new Minecraft versions within days.

**Weaknesses:**
- No brain. No goals, no planning, no combat.
- It runs inside your own game client and moves *your* character, so it's not a separate helper standing beside you.
- It's a client mod, not a library you call from a server, so wiring an AI to it takes extra work.

**What to steal:** Its layer-by-layer survival building approach, and possibly the mod itself for a "work on my character while I'm away" mode.

---

### 8. The "eyes and fingers" research agents: VPT, STEVE-1, JARVIS-1, Optimus-3

**What they are:** Neural networks that look at raw screen pixels and output mouse and keyboard actions, like a human player. VPT (OpenAI, 2022) learned from 70,000 hours of YouTube. STEVE-1 added "follow a text instruction" for only $60 of training. JARVIS-1 added memory and a planner. Optimus-3 (2025) is a 6.8B-parameter model with task specialists and is the current research leader.

**Strengths:**
- The most human-like setup: same screen, same controls, no special access.
- Real scientific progress on "general agents" that could work in any game.

**Weaknesses:**
- **Unreliable at long tasks.** JARVIS-1 gets a diamond pickaxe about 12.5% of the time within an hour. Optimus-3 reaches about 15% on diamond-level tasks. A beginner human does better.
- Needs GPUs and research environments that are pinned to old Minecraft versions.
- No published dragon kills, big builds, or mansion clears.
- A 2026 public leaderboard (AgentBeats, using the MCU benchmark) shows general multimodal agents, even ones using GPT-5.4, scoring around 40 out of 130 on long tasks.

**What to steal:** Not much for us right now. They're worth watching for one narrow thing later: reading the screen to judge whether a build *looks* good.

---

### 9. Odyssey (IJCAI 2025)

**What it is:** A research framework that gives an LLM agent a large ready-made skill library (40 basic skills and 183 combined ones) plus a small open model (LLaMA-3 8B) fine-tuned on 390,000 Minecraft Wiki questions.

**Strengths:** Shows that a small, cheap, local model can do well *if* the skills are good. Their tests found that removing the skill library hurts far more than using a smaller model.

**Weaknesses:** Research code built on Voyager's old setup. Not maintained for current versions.

**What to steal:** Evidence that investing in skills beats investing in a bigger model.

---

### 10. Building-focused projects

These matter most for the castle goal.

- **minecraft-agentic (NoblerWorks-HQ):** A crew of four Mineflayer bots builds castles that an LLM designs. Its key finding is very useful: when the model had to list a castle block by block, it got lazy and returned **74 blocks with holes in the walls (scored 3/10)**. When it was given shape tools instead (like "make a wall from here to there"), it produced **2,773 blocks with nothing missing (scored 10/10)**. The catch: the bots build with `/setblock`, `/fill`, and `/tp`, which are admin commands. That's creative-mode building, not survival.
- **CraftUtopia (AAMAS 2026):** Turns a single 2D picture into a 3D Minecraft blueprint, then splits the build among a manager, foremen, and workers in separate zones. It hit 100% success on three test builds over five tries each.
- **MineCollab (Mindcraft team):** Blueprint-building tasks with a score for how close the result is to the blueprint. Good as a test suite.
- **Benchmarks:** MineCEraft (723 construction tasks with automatic checking, EMNLP 2026), MineAnyBuild (4,000 spatial planning tasks, NeurIPS 2025), and MineBench (a public arena where models' voxel builds are voted on head to head).

**The pattern:** Design and build are separate jobs. Models design well when they get shape tools instead of block lists. Building works best when the blueprint is split into zones and checked against the finished result. Nobody has combined this with legitimate survival gathering.

---

### 11. Project Sid (Altera)

**What it is:** A simulation of 10 to over 1,000 AI agents living together in Minecraft. They formed jobs, rules, and even culture and religion.

**Why it matters to us:** Mostly it doesn't. It's about societies, not about getting one job done reliably. It's a reminder that "more agents" is a different problem from "better agent."

---

## Patterns that work

1. **Honest, careful actions.** Every action should report what actually happened in the world. (Mine AI MCP)
2. **Reflexes that don't wait for the model.** Eating, fleeing, and fighting back happen instantly in code. (Mine AI MCP, mc-agents)
3. **A smart planner called rarely, plus a fast cheap decider.** (Astra + Jev: 35 planner calls for a whole dragon run)
4. **Saved, reusable skills.** (Voyager, Odyssey)
5. **Memory in a database, not in the chat history.** (Mine AI MCP's SQLite)
6. **Check the work against the game, not against the model's word.** Advancements, inventory counts, blocks compared to the blueprint. (Voyager self-check, Astra's victory file)
7. **Design with shapes, build in zones, compare to the blueprint.** (minecraft-agentic, CraftUtopia)

## Patterns that fail

1. **Cheating to look good.** Teleporting, invincibility, and spawning blocks (Steve AI, minecraft-agentic). Demos look great, but it isn't survival.
2. **One giant ever-growing conversation.** It works with a 1M-token model, but it's expensive and fragile. (Most of Mine AI MCP's cost)
3. **Asking the model to type out thousands of blocks.** It cuts corners. (minecraft-agentic's 3/10 castle)
4. **Many chatty agents too early.** Coordination costs more than it helps. (MineCollab)
5. **Tying yourself to one old Minecraft version.** Voyager (1.19), Steve AI (1.20.1), and Astra + Jev (1.16.5) all got stranded.

## Gaps nobody has filled yet

- A legitimate survival castle: gather materials, build at scale, protect what's built, check against the blueprint.
- Clearing a woodland mansion: finding one, fighting groups of illagers (vindicators with axes, evokers summoning vexes), mapping a maze of rooms, and lighting it up so nothing respawns.
- Long missions that pick up where they left off after a crash, death, or restart without starting over.
- Running on the newest Minecraft (26.3) without waiting months for the libraries to catch up.

These gaps are where this project can be better than everything above. See `02-how-we-make-it-better.md`.

---

## Sources

- Steve AI: [github.com/YuvDwi/Steve](https://github.com/YuvDwi/Steve), [technical deep dive](https://github.com/YuvDwi/Steve/blob/main/TECHNICAL_DEEP_DIVE.md), [issue #5](https://github.com/YuvDwi/Steve/issues/5)
- Mindcraft: [github.com/mindcraft-bots/mindcraft](https://github.com/mindcraft-bots/mindcraft), [v0.1.4 release](https://github.com/mindcraft-bots/mindcraft/releases/tag/v0.1.4), [MineCollab paper](https://mindcraft-minecollab.github.io/)
- Voyager: [github.com/MineDojo/Voyager](https://github.com/MineDojo/Voyager)
- Mine AI MCP: [github.com/aibengineering/mine-ai-mcp](https://github.com/aibengineering/mine-ai-mcp), [run dataset](https://huggingface.co/datasets/aibengineering/beat-the-game-minecraft), [limitations doc](https://github.com/aibengineering/mine-ai-mcp/blob/main/docs/mcp/limitations.md)
- Astra + Jev: [github.com/rmalde/minecraft-agent](https://github.com/rmalde/minecraft-agent), [write-up](https://ascii.jp/en/elem/000/004/436/4436545/)
- mc-agents: [github.com/jblemee/mc-agents](https://github.com/jblemee/mc-agents)
- Baritone: [github.com/cabaletta/baritone](https://github.com/cabaletta/baritone), [releases](https://github.com/cabaletta/baritone/releases)
- STEVE-1: [github.com/Shalev-Lifshitz/STEVE-1](https://github.com/Shalev-Lifshitz/STEVE-1)
- JARVIS-1: [arxiv.org/abs/2311.05997](https://arxiv.org/abs/2311.05997)
- Optimus-3: [arxiv.org/abs/2506.10357](https://arxiv.org/abs/2506.10357)
- Experience transfer study (CVPR 2026): [paper](https://openaccess.thecvf.com/content/CVPR2026/papers/Li_Experience_Transfer_for_Multimodal_LLM_Agents_in_Minecraft_Game_CVPR_2026_paper.pdf)
- Odyssey: [arxiv.org/abs/2407.15325](https://arxiv.org/abs/2407.15325)
- minecraft-agentic: [github.com/NoblerWorks-HQ/minecraft-agentic](https://github.com/NoblerWorks-HQ/minecraft-agentic)
- CraftUtopia: [ACM AAMAS 2026](http://dl.acm.org/doi/10.65109/PUIJ7087)
- MineCEraft: [arxiv.org/abs/2608.28884](https://arxiv.org/abs/2608.28884)
- MineAnyBuild: [NeurIPS 2025](https://proceedings.neurips.cc/paper_files/paper/2025/file/8048e2b9b631b15e1158f36275d8fc11-Paper-Datasets_and_Benchmarks_Track.pdf)
- MineBench: [github.com/Ammaar-Alam/minebench](https://github.com/Ammaar-Alam/minebench)
- Project Sid: [github.com/altera-al/project-sid](https://github.com/altera-al/project-sid)
- AgentBeats Minecraft leaderboard: [agentbeats.dev](http://agentbeats.dev/agentbeater/minecraft-green-agent)
