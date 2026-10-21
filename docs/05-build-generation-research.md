# Generating Builds: What AI Can Design Today (October 2026)

Research date: October 21, 2026. This builds on `01-sota-research.md` (agents) and zooms in on one question: **how complex a structure can AI generate as a blueprint, so Minegoon only has to follow it in survival?**

---

## The short story

Splitting "design" from "build" is the right call, and the design half is much further along than the build half.

Today, if you just want a file you can paste in creative mode, AI can generate huge things. On MineBench, a public arena where models generate voxel builds and people vote on them, top models write small programs that output builds of **one to two million blocks**: a Hogwarts, a pagoda garden, a Mona Lisa you can walk across. A Minecraft mod called CRAFT AI has Claude Code build a **35,000-block castle in about 15 minutes**, and it already runs on Minecraft 26.3. Several tools turn a sentence or a photo into a `.litematic` or `.schem` file in under a minute.

The trick behind every good result is the same: **the model doesn't list blocks, it writes code.** It writes "a cylinder tower here, a wall from here to there, a cone roof on top," and a program expands that into blocks. Models that type blocks one by one produce small, holey builds. Models that write generator code produce big, complete ones.

But "big and pretty" is not the same as "something a survival bot can actually build." Three gaps show up:

1. **Most generated builds aren't real Minecraft architecture.** MineBench's block palette has no stairs, slabs, doors, fences, or glass panes at all. Everything is full cubes. It's voxel art. Real builds need blocks that face directions and sit in half-spaces.
2. **Nobody generates with survival in mind.** Generators happily use gold blocks, glowstone, and 30 colors of wool. They float blocks in mid-air and put sand where it will fall. None of them think about what it costs to gather the materials.
3. **The scale is far beyond what survival can deliver.** A 2-million-block MineBench build would take a bot something like a thousand hours to gather and place. A 5,000 to 35,000-block build is the realistic range for one bot over one or two long sessions.

The research world backs this up. The best LLMs score under 40% on MineCEraft, a benchmark that checks whether a construction is the right shape, size, and actually stable. And the team that won this year's big AI settlement competition (GDMC 2026) tried LLMs for building design, said "the output made no sense," and won with hand-made building templates instead. AI is great at the layout and the big picture; fine architectural detail is still best done with good templates and good tools.

So the plan is: **let the model write generator code against our own shape language, but make that language survival-aware.** It should only offer blocks we can realistically gather, work out stair and door directions automatically, reject floating or falling blocks, and print a bill of materials and a time estimate before anyone approves the build. Then Minegoon follows the blueprint section by section, as already planned in `02-how-we-make-it-better.md`.

---

## The ceiling at a glance

| What | How big | Real block states (stairs, slabs, doors)? | Survival-aware? | Source |
|---|---|---|---|---|
| MineBench top builds (Hogwarts, Pagoda Garden) | 1.2M to 2M blocks | No, full cubes only | No | minebench.ai gallery |
| CRAFT AI castle (Claude Code + 20 in-game tools, MC 26.3) | 34,738 blocks in about 15 min | Yes | No, places blocks directly | craftai.world |
| mcb (minecraft-builder-skill) city and castle generators | 200 x 200 city in its docs; a scripted 445k-block campus scene | Yes, handles rotation | No, uses `/fill` | github.com/wzhaoMS/minecraft-builder-skill |
| BlockGPT, Structmatic (text or image to schematic) | Single builds, sized by user | Not stated | No | blockgpt.ai, structmatic.com |
| GDMC 2026 winner (full adaptive city: castle, market, farms, harbours) | Whole settlement on an unknown map | Yes, hand-made templates | No | Leiden University, GDMC wiki |
| Dream-Cubed (learned diffusion model) | 32 x 32 x 32 chunks, tiled outward | Limited (117 block types) | No | arXiv 2604.22847 |
| MineCEraft (accuracy benchmark) | Small to medium tasks | Yes | Checks stability | arXiv 2608.28884 |

---

## Zoom in: each approach

### 1. LLMs writing generator code (the strongest approach)

**MineBench (Ammaar-Alam/minebench).** A model gets a prompt like "a medieval castle with four towers" and a tool called `voxel.exec`. It writes JavaScript using three primitives (`block`, `box`, `line`) plus math and a random number generator. The code runs in a sandbox and the result is rendered. People vote blind between two builds, and models get a rating.

- Current top of the leaderboard (Bradley-Terry rating): GPT-6 Astra Pro 2250, Gemini 4 Argon 2245, GPT-6.1 Sol Pro 2189, Claude Opus 5.5 2160.
- Grid sizes go up to 512 for normal builds (up to 4 million blocks) and 2048 or 8192 for "world" builds.
- Gallery examples: Hogwarts at 1,955,996 blocks, Pagoda Garden at 1,225,406, Mona Lisa at 1,049,372.
- It exports to `.schem`, so you can paste builds into Minecraft with WorldEdit.
- **The catch for us:** I checked the palette file. The simple palette has 25 blocks and the advanced one adds 300 more, and **not one of them is a stair, slab, door, fence, pane, or trapdoor.** The prompt also pushes models toward millions of blocks and toward expensive accent blocks (gold, glowstone, wool). Great for testing how well a model thinks in 3D, wrong target for survival.

**CRAFT AI (craftai.world).** A Fabric, Forge, and NeoForge mod for **Minecraft 26.3** that launches your own Claude Code with 20 tools inside the game. Published builds: a 34,738-block castle in 15 minutes, a 20,459-block pagoda in 10, a 5,100-block villa with a pool in 8. One click undoes a build. It places blocks directly, so it's creative-mode building. It shows a model plus good tools can make detailed, real-block-state builds at castle scale on our exact Minecraft version.

**mcb / minecraft-builder-skill (wzhaoMS).** A command-line tool plus an agent skill file. It's the closest thing to the blueprint office we planned:

- Blueprints are layer-by-layer ASCII JSON, a small JS shape language (`fill`, `sphere`, `place`, `transformed`), or any `.schem`, `.nbt`, or `.litematic`.
- 24 parametric generators: houses in several styles, castle, tower, skyscraper, bridge, road, lighthouse, windmill, city, pixel art.
- It handles the fiddly parts: stair, door, and log rotation, placement order, chunk loading.
- `mcb preview` renders an isometric view, a top view, and side views into one PNG so the agent can look at its own design before building. `--verify` checks the result against the blueprint.
- Builds with `/fill` on a Paper 1.21.11 server, so again creative-style.

**Promptcraft and VibeBuild.** Smaller projects with the same idea. Promptcraft has Claude Code write a Python generator that outputs a `.schem`. VibeBuild uses a planner agent to write an ordered build plan and an executor agent to carry it out through WorldEdit, with a ghost preview before placing.

**Cairn (kage1020/Cairn).** Not a working tool yet (a design spec, draft 2026.6, no compiler), but the ideas are the best thought-out in the space:

- You describe intent (walls, roofs, windows, symmetry) and the compiler works out the exact blocks.
- **"Derive by default, override when it matters"**: stair facing, door orientation, pane and fence connections, and torch vs wall torch are worked out from neighbours. You only write them when the direction is the design choice (an upside-down stair under the eaves, a horizontal log beam).
- Themes separate "where" from "what": the structure says "roof material here" and a theme picks spruce or deepslate.
- The compiler doubles as a linter, and precision comes from a fix-it loop, not one perfect generation.

**What to steal:** generator code instead of block lists (all of them), the preview-image self-check (mcb), and derived block states plus themes (Cairn).

---

### 2. Text or image to schematic services

- **BlockGPT:** text or image in, `.schem`, `.litematic`, or `.mcstructure` out in about 30 seconds. You can set size and palette first. 15,000 public builds in a free gallery.
- **Structmatic:** text or image becomes a textured 3D model, whose surfaces are matched to Minecraft blocks, then edited in a browser studio.
- **ObjToSchematic:** converts any 3D model (from Hunyuan3D, Trellis, or its own generator) into blocks. Has an in-game mod for 26.1 and 26.2.
- **BlockArchitect and MC Shapes:** output vanilla `.mcfunction` datapacks. BlockArchitect is upfront that it does geometric builds well but not organic ones (dragons, statues). MC Shapes uses full blocks only.

**Where they fit:** the 3D-model route (text to mesh to blocks) is the best way to get organic shapes like a dragon statue or a giant tree. It can't produce sensible interiors, and the block choice is driven by color, not cost. Useful for decorative pieces, not whole bases.

---

### 3. Research systems

- **T2BM (IEEE CoG 2024):** the LLM writes JSON "sections" (wall from A to B, hollow, material X; door at P with facing and hinge), then a repair step fixes errors. The first clear demonstration that a component format beats raw blocks.
- **APT (2024):** the LLM writes a layout summary first, then Python code that generates the blueprint, and Mineflayer builds it. It handled redstone, and the model **started adding scaffolding to its build order without being asked**. A good sign that models can reason about buildability if the format lets them.
- **CraftUtopia (AAMAS 2026):** a single 2D image becomes a 3D blueprint, then the build is split into separate zones for a manager, foremen, and workers. 100% success on three builds over five trials each.
- **Dream-Cubed (Sakana AI and NYU, April 2026):** the first big diffusion model trained directly on Minecraft blocks: 280M parameters, 32 x 32 x 32 chunks, trained on billions of blocks from natural terrain and six professional maps. It can fill in or extend an area around blocks you placed. Limits: Minecraft 1.12.2 data, a reduced vocabulary of 117 block types, decorative blocks like buttons and trapdoors removed, and the authors say results "do not compare to the quality of the original map." Interesting for terrain and filler, not ready to design a castle.

---

### 4. Reality checks

**MineCEraft (EMNLP 2026 Findings).** 723 construction tasks with automatic checks. Under the strict rule that every requirement must pass, the best models got 39.6% (GPT-5 mini) and 38.3% (Gemini 3 Pro). Humans with construction-engineering degrees got about 90%. The weakest areas are the ones that matter for survival: **shape (50 to 56%) and structural stability (43 to 57%)**, against 94 to 99% for humans. These are 2025-era models, so current models will do better, but the lesson stands: the model's first draft needs automatic checking.

**GDMC 2026 (the Generative Design in Minecraft settlement competition).** Programs must build a settlement on a map they've never seen, with no human help. The 2026 winner (team Merge Conflict, Leiden) got the highest aesthetics score in the contest's history. They tried LLMs and said "the output made no sense"; the models "struggled with reasoning about three-dimensional space." They won with a large library of hand-made building templates, plus algorithms that decide where things go, run roads along the terrain, and adapt each house to its biome (vines in jungles, no water features in deserts). The organizer's summary: "Human teams with AI tools win."

**minecraft-agentic** (from `01-sota-research.md`): listing blocks gave 74 blocks with holes (3/10), shape tools gave 2,773 blocks with nothing missing (10/10).

---

## What survival changes

A creative-mode generator only has to look good. Ours also has to be gatherable and placeable. Five things matter.

### Cost of materials

Blocks vary enormously in effort. A rough grouping:

| Tier | Examples | Why |
|---|---|---|
| Cheap | Cobblestone, dirt, logs and planks, glass (from sand), stone and stone bricks (one smelt) | Found everywhere, fast to mine or chop |
| Medium | Deepslate variants, terracotta (smelted clay), concrete (sand, gravel, dye), bricks, copper | Need travel, smelting, or crafting chains |
| Expensive | Wool in many colors, quartz and glowstone (Nether), prismarine (ocean monument), gold or iron blocks (9 ingots each) | Rare, far away, or need farming |

The generator should get a **palette budget**: mostly cheap blocks, a few medium ones for accents, expensive ones only if you ask.

### Block states

Stairs, slabs, doors, trapdoors, fences, panes, and logs all have orientation or shape. A survival bot also has to *place* them correctly, which depends on where it stands and which face it clicks. Deriving states automatically (the Cairn approach) and checking them after placing is a must.

### Buildability

- **Support:** every block needs something to be placed against at the moment it's placed. Build bottom-up and add temporary scaffolding where needed (as APT's model did on its own).
- **Gravity blocks:** sand, gravel, and concrete powder fall. Reject them unless supported.
- **Reach:** a player can place about 4.5 blocks away. Tall towers and overhangs need scaffold plans.
- **Fragile or special blocks:** water, lava, torches, and redstone need special placement order.

### Scale

A rough model (our estimate, not a measurement): a bot gathering at about 1 block per second and placing at about 1 block per second, including walking, scaffolding, and restocking.

| Build | Blocks | Rough survival time |
|---|---|---|
| CRAFT AI villa | 5,100 | about 3 hours |
| CRAFT AI castle | 34,738 | about 20 hours (two or three long sessions) |
| MineBench Hogwarts | 1,955,996 | over 1,000 hours (not realistic) |

So the **sweet spot is 3,000 to 30,000 blocks**, and the generator should take a block budget as an input, not maximize block count like MineBench does. Hollow walls, a few heights of solid foundation, and detail on the outside surfaces go a long way.

### Hand-off format

`.litematic` is the standard for survival building. Litematica shows a ghost of the build in-world and keeps a live material list, and Baritone can build from it using your inventory (`#litematica`, `allowInventory`, `buildInLayers`). Baritone doesn't produce a material list itself, so we compute our own.

---

## Recommended pipeline for Minegoon

This refines the "blueprint office" in `02-how-we-make-it-better.md`.

```mermaid
flowchart LR
  prompt[Your request plus budget]
  model[LLM writes generator code]
  dsl[Survival-aware shape language]
  check[Checks: support, gravity, states, palette, size]
  preview[Preview images]
  approve[You approve]
  out[.litematic plus bill of materials plus section jobs]
  bot[Minegoon gathers and builds]

  prompt --> model --> dsl --> check
  check -- fails --> model
  check --> preview
  preview -- model critiques --> model
  preview --> approve --> out --> bot
```

1. **Input:** your request plus a budget (for example "castle, about 15,000 blocks, cheap and medium tiers only, on this 60 x 60 plot").
2. **Generate:** the model writes code against our shape language (walls, towers, roofs, battlements, window rows, arches, stairs, floors, rooms) with themes for materials. Block states are worked out by the compiler. A library of hand-made parametric parts (gatehouse, tower top, roof styles) gives the GDMC-style quality boost.
3. **Check automatically:** unsupported or falling blocks, invalid states, blocks outside the palette budget, size over budget. Failures go back to the model as a fix list.
4. **Preview:** render isometric, top, and side images. The model looks at them and fixes what's ugly or wrong (the mcb approach). Then you approve it in the dashboard.
5. **Output:** a `.litematic` for the in-game ghost, a bill of materials worked back through recipes (stone bricks come from stone, which comes from cobblestone plus fuel), a time estimate, and the build cut into sections that become jobs on the job board.
6. **Build:** gather, stock chests at the site, build each section bottom-up, compare against the blueprint, repair, as already planned.

**Organic extras** (statues, dragons, giant trees) can come from the text-to-3D-model route through ObjToSchematic, limited to the same palette budget and passed through the same checks.

**What we don't need to build ourselves:** `.litematic` and `.schem` reading and writing, and possibly mcb's preview renderer and generators as a starting point. Check its license before reusing code.

---

## Open questions to test

- How good is a current top model (Claude Opus 5.5, Gemini 4 Argon, GPT-6 Astra Pro) at writing generator code against a shape language with a block budget? MineBench ratings measure looks, not budget discipline.
- How much does the preview self-critique loop improve results, and how many rounds is it worth?
- What is the real placing and gathering speed of our bot? The time estimates above are guesses until the lab measures them.
- Is a library of hand-made parts worth the effort up front, given the GDMC result?

---

## Sources

- MineBench: [minebench.ai](https://minebench.ai/), [gallery](https://minebench.ai/gallery), [repo](https://github.com/Ammaar-Alam/minebench), [voxel.exec docs](https://github.com/Ammaar-Alam/minebench/blob/master/docs/voxel-exec-raw-output.md), [export docs](https://github.com/Ammaar-Alam/minebench/blob/master/docs/build-export-import.md), [palette file](https://github.com/Ammaar-Alam/minebench/blob/master/lib/blocks/palettes.json), [leaderboard mirror](https://atlas.kevinhu.io/benchmarks/minebench-bradley-terry-rating)
- CRAFT AI: [craftai.world](https://www.craftai.world/), [write-up](https://www.curatedai.net/builds/400)
- mcb / minecraft-builder-skill: [github.com/wzhaoMS/minecraft-builder-skill](https://github.com/wzhaoMS/minecraft-builder-skill)
- Promptcraft: [github.com/cgoulart35/Promptcraft](https://github.com/cgoulart35/Promptcraft)
- VibeBuild: [github.com/WilliamStanton/vibe-build](https://github.com/WilliamStanton/vibe-build)
- Cairn: [github.com/kage1020/Cairn](https://github.com/kage1020/Cairn), [blockstate spec](https://cairn.kage1020.com/spec/blockstate/)
- BlockGPT: [blockgpt.ai](https://blockgpt.ai/); Structmatic: [structmatic.com](https://structmatic.com/); ObjToSchematic: [objtoschematic.com](https://objtoschematic.com/); BlockArchitect: [blockarchitect.de](https://blockarchitect.de/); MC Shapes: [guide](https://mcshapes.vip/blog/ai-minecraft-build-generator-guide)
- T2BM: [arxiv.org/abs/2406.08751](https://arxiv.org/abs/2406.08751)
- APT: [arxiv.org/abs/2411.17255](https://arxiv.org/abs/2411.17255)
- CraftUtopia: [ACM AAMAS 2026](http://dl.acm.org/doi/10.65109/PUIJ7087)
- Dream-Cubed: [arxiv.org/abs/2604.22847](https://arxiv.org/abs/2604.22847), [project page](https://pub.sakana.ai/dream-cubed/)
- MineCEraft: [arxiv.org/abs/2608.28884](https://arxiv.org/abs/2608.28884)
- GDMC 2026: [competition page](https://gendesignmc.wikidot.com/wiki:2026-settlement-generation-competition), [Leiden University article](https://www.universiteitleiden.nl/en/news/2026/09/can-ai-build-a-beautiful-city-in-minecraft-these-leiden-students-did-it-better)
- Baritone settings: [baritone.leijurv.com](https://baritone.leijurv.com/baritone/api/Settings.html), [material list issue](https://github.com/cabaletta/baritone/issues/2990)
