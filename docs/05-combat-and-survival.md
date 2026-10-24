# Combat Built for Staying Alive

Research date: October 24, 2026. Builds on `01-sota-research.md` and `02-how-we-make-it-better.md`.

---

## The short story

PvP code is the wrong starting point, but it has a few useful parts.

PvP plugins are tuned for one job: kill another player as fast as possible. They chase critical hits, strafe in circles, sprint-tap to knock the other player around, and some of them send packets that a normal player can't (servers treat that as cheating). All of that assumes the fight is the goal. For us the fight is a cost. Every hit we take, every minute spent fighting, and every death pulls the bot away from the job it was doing.

So we flip the question. Instead of "how do I win this fight," the bot asks "what is the cheapest way to still be alive, with my job still going, a minute from now?" Usually that means not fighting at all: walk around the creeper, wall off the skeleton, step into a 1-block gap so only one zombie can reach you, or go hide and eat. It only fights when fighting is the cheapest way out, and it stops as soon as it is safe again, not when the enemy is dead.

The good news: the best open-source example of exactly this already exists, and it's the same project our plan already borrows the bot's body from. **Mine AI MCP's survival module** decides each encounter between fight, hide, run, block, and retreat to heal, has a "defend only, never chase" setting the AI can turn on, and goes back to the original job once the danger has passed. It's MIT licensed, about 15,000 lines of TypeScript with 63 test files, and it kept Claude alive through the only fully logged survival dragon kill.

PvP libraries still have three skills worth borrowing: knowing when someone is aiming a bow at you, timing a shield, and switching to an axe when the enemy is shielding. We take those ideas, not the whole library.

---

## The candidates

| Project | What it is | Goal it optimizes | License | Fit for us |
|---|---|---|---|---|
| **Mine AI MCP survival module** | Mineflayer, TypeScript | Stay alive and keep the job going | MIT | **Use as the base** |
| nxg-org/mineflayer-custom-pvp | Mineflayer plugin | Win sword and bow duels | Unclear (see below) | Borrow ideas only |
| PrismarineJS/mineflayer-pvp | Mineflayer plugin | Basic attack-a-target loop | MIT | Too basic |
| Mindcraft "modes" | Mineflayer, JavaScript | Simple on/off reflexes | MIT | Reference for a minimal version |
| Astra + Jev combat lab | Mineflayer test server | Test fights in isolation | None stated | Copy the testing habit |
| pvp-bot-fabric | Server-side Fabric mod | PvP bots that fight anything | Unlicense | Wrong shape, some ideas |

---

## Zoom in: each candidate

### 1. Mine AI MCP survival module (the pick)

Repo: [aibengineering/mine-ai-mcp](https://github.com/aibengineering/mine-ai-mcp), folder `src/survival`, docs in `docs/survival/`.

**How it thinks, plainly.** Every time danger shows up, one function looks at the whole picture (health, hunger, equipment, which mobs are near and which of them actually attacked us, incoming arrows and fireballs, what has already been tried and failed) and picks exactly one response:

- **Evade:** walk away until every threat is at least 36 blocks off, with one 15-second attempt.
- **Hide:** build or use cover, then eat and heal inside it (one 90-second hold).
- **Deflect:** face incoming arrows or fireballs with the shield.
- **Defend:** swing at something that is already in reach.
- **Fight:** actually go after a mob, only if health is at least 12 by default.

Below 8 health it protects itself before contact. Creepers are priced from beyond the range where they notice you, so routes bend away early. Players, passive mobs, and neutral mobs that haven't attacked are never targeted.

**The setting that matches your goal.** `combat.engagement` can be `respond_to_threats` (the default) or `defend_only`. In `defend_only`, the bot never starts a chase. It still blocks, swings at whatever is in its face, and retreats. The AI sets this through one tool (`set_survival_policy`) with a lifetime ("for this trip," "until health is back to 18") and a written reason, so it's always visible what was changed and why.

**It goes back to work.** When the danger ends (target dead, target gone, safe distance reached, healed in shelter), the interrupted job resumes and the job's finish line is re-checked, because the reflex may have used up blocks or food or moved the bot.

**It records honest evidence.** Every reply includes what danger was seen, what was decided, who owned the bot's body, and how it ended. That plugs directly into our job board and inspector.

**Honest limits, from its own docs:**
- Not yet qualified: surviving a full night with low health while keeping a job pending, and fortress collection under multiple shooters. Skeleton crossfire still fails native tests.
- Only one kind of fighting cover so far.
- No potion strategy, no automatic armor swapping.
- It never targets players, including griefers. Fine for single-player survival, a gap on a public server.
- Pinned to Minecraft 1.21.4 and its own forks of Mineflayer and minecraft-data. Porting to 26.3 is the same task already listed for Phase 2.
- About 15,000 lines with strict ownership rules. We should port it whole rather than cherry-pick files.

### 2. nxg-org/mineflayer-custom-pvp (the strongest pure PvP plugin)

Repo: [nxg-org/mineflayer-custom-pvp](https://github.com/nxg-org/mineflayer-custom-pvp). Version 1.7.16, April 2026.

**Good at:** accurate hit detection, 80 to 95% critical hits, strafing, shield handling, axe swap against shields, bow aim with projectile prediction, and **detecting when someone is aiming at you**.

**Why not as a dependency:**
- It's built to win duels. Several features are things a normal player can't do ("packet" criticals, "blatant" shielding, instant rotation), which conflicts with our no-cheating rule.
- The license is contradictory. `package.json` says GPL-3.0, the README badge says MIT, and the repo has no LICENSE file. If GPL applies, shipping it inside Minegoon would force Minegoon under GPL too.
- The README lists Minecraft 1.8 to 1.19 support.

**What to borrow:** the "am I being aimed at" check, shield timing, and axe-versus-shield switching, reimplemented in our own code. Mine AI MCP already has its own projectile and aimed-bow detection, so compare the two before writing anything.

### 3. PrismarineJS/mineflayer-pvp

The official basic plugin. "Attack this entity, follow it, stop." MIT. The repo still gets commits, but the last npm release is 1.3.2 from 2022. No sense of health, retreat, or threats. Too simple for what we need.

### 4. Mindcraft modes

Mindcraft has simple always-on reflexes: `self_preservation` (water, fire, falling sand, low health), `cowardice` (run from anything hostile within 16 blocks), and `self_defense` (fight anything hostile within 8 blocks). The whole file is about 450 lines and easy to read. A good reminder that "run" and "fight" are separate reflexes with an order between them, but it has no idea of health thresholds per decision, no shields, no resuming the job.

### 5. Astra + Jev combat lab

Not a combat system to reuse, but a habit: a separate test server where fights are set up with prepared items and positions and replayed many times. Their write-up is careful to say a lab result isn't a real survival result. We already plan the same thing in `02-how-we-make-it-better.md`, section 9.

### 6. pvp-bot-fabric

A server mod that spawns fake players for PvP. It has nice survival-flavored settings (retreat on low health, auto-shield on low health, auto-totem, auto-eat), but it runs inside the server, not as a Mineflayer client, so it doesn't fit our body at all.

---

## How we change the goal from "kill" to "survive"

The point of this section is that a different goal means a different scoreboard. If we measure kills, the bot learns to fight. If we measure staying alive while getting work done, it learns to avoid fights.

**The order of preference for every encounter:**

1. **Don't meet it.** Route around threats; light and wall the areas we work in; don't travel at night without a reason.
2. **Make it unable to reach us.** Pillar up, block a doorway, stand in a 1-wide gap.
3. **Leave.** Walk away to a safe distance, then resume the job.
4. **Block.** Shield up against arrows, fireballs, and melee.
5. **Hide and heal.** Seal in, eat, wait until health is back.
6. **Fight.** Only when it's cheaper than the options above (for example, a single zombie between us and the chest), and stop the moment we're safe.

This is what Mine AI MCP's decision already does. Our part is to set the defaults to lean further toward the top of the list, and to give the planner `defend_only` as the normal mode for anything that isn't a combat mission.

**What we measure in the lab (instead of kills):**

| Number | Why |
|---|---|
| Deaths per hour of play | The main score |
| Health lost per encounter | Did we take hits we could have avoided? |
| Encounters avoided versus fought | Should go up over time |
| Time from danger to safe | Faster escape means less risk |
| Job resumed after danger, yes or no | Survival that loses the job is half a win |
| Blocks, food, and durability spent per encounter | Fighting costs resources too |

**Lab scenarios to start with:** a full night in the open with only a wooden sword; a creeper plus a skeleton on open ground; a zombie group in a cave tunnel; a skeleton shooting from a ledge while we mine; low health at dusk with food but no shelter. Each run passes if the bot is alive at the end and the job it was doing is still going.

**Where the combat missions fit.** The dragon and the mansion still need real fighting. Those become explicit hunts (Mine AI MCP calls them `collect_mob_drop` requests), where fighting a named target is allowed, but the health thresholds and the "hide or leave below 8 health" rules still apply. Even then, the bot fights to finish the job, not to win.

---

## Plan changes

- **Phase 2 (body):** port Mine AI MCP's whole survival module along with the rest of its body. Set `combat.engagement` to `defend_only` as Minegoon's default and raise `engage_min_health` if lab results support it.
- **Phase 3 (brain loop):** the planner switches to `respond_to_threats` only for combat jobs (hunts, dragon, mansion rooms). The inspector checks "alive and job still active" after every danger event.
- **Lab:** add the survival scenarios above, scored with the survival numbers, not kill counts. Start with the two gaps Mine AI MCP admits to: the full night and skeleton crossfire.
- **Later:** decide whether the bot should evade players who attack it. Mine AI MCP never targets players, which is the right default, but it doesn't say it flees from them either.

---

## Sources

- Mine AI MCP survival docs: [README](https://github.com/aibengineering/mine-ai-mcp/blob/main/docs/survival/README.md), [survival policy](https://github.com/aibengineering/mine-ai-mcp/blob/main/docs/survival/survival-policy.md), [testing](https://github.com/aibengineering/mine-ai-mcp/blob/main/docs/survival/testing.md)
- mineflayer-custom-pvp: [GitHub](https://github.com/nxg-org/mineflayer-custom-pvp), [npm](https://www.npmjs.com/package/@nxg-org/mineflayer-custom-pvp)
- mineflayer-pvp: [GitHub](https://github.com/PrismarineJS/mineflayer-pvp)
- Mindcraft modes: [src/agent/modes.js](https://github.com/mindcraft-bots/mindcraft/blob/main/src/agent/modes.js)
- Astra + Jev: [GitHub](https://github.com/rmalde/minecraft-agent)
- pvp-bot-fabric: [GitHub](https://github.com/Stepan1411/pvp-bot-fabric)
