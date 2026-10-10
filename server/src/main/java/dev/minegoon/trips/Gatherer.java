package dev.minegoon.trips;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Digging;
import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Look;
import dev.minegoon.actions.Miner;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Pickup;
import dev.minegoon.jobs.Step;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.WorldView;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Trips out into the world: finding blocks or animals that drop what a job
 * needs, walking to them, and collecting the drops. When nothing is in range
 * it explores outward a few times before giving up.
 */
public final class Gatherer {

    private static final int MAX_EXPLORES = 5;
    private static final int EXPLORE_DISTANCE = 40;
    private static final int MAX_CANDIDATES = 64;
    private static final int RESCAN_EVERY = 8;
    private static final double BURIED_PENALTY = 6;
    private static final double HIT_RANGE = 2.8;
    private static final int MAX_CHASES = 24;

    private final Body body;
    private final Mover mover;
    private final Miner miner;
    private final Pickup pickup;
    private final Retreat retreat;
    private final int radius;

    public Gatherer(Body body, Mover mover, Miner miner, Pickup pickup, Retreat retreat, int radius) {
        this.body = body;
        this.mover = mover;
        this.miner = miner;
        this.pickup = pickup;
        this.retreat = retreat;
        this.radius = radius;
    }

    /** Mines until the inventory holds {@code target} of the item. */
    public void gather(Step.Gather step, int target) {
        Set<Material> minable = body.main().call(() -> harvestable(step.blocks()));
        if (minable.isEmpty()) {
            throw new ActionFailed("I don't have a tool that can harvest " + ItemSet.of(step.blocks()).name());
        }
        Checkpoint checkpoint = retreat.guarding(neededFor(minable));
        Set<Pos> skipped = new HashSet<>();
        Deque<Pos> candidates = new ArrayDeque<>();
        int explores = 0;
        int minedSinceScan = 0;
        while (count(step.item()) < target) {
            checkpoint.check();
            if (candidates.isEmpty() || minedSinceScan == RESCAN_EVERY) {
                candidates = scan(minable, skipped);
                minedSinceScan = 0;
            }
            if (candidates.isEmpty()) {
                if (explores++ == MAX_EXPLORES) {
                    throw new ActionFailed("I found no " + ItemSet.of(minable).name() + " within reach");
                }
                explore(checkpoint);
                continue;
            }
            Pos block = candidates.poll();
            skipped.add(block);
            boolean still = body.main().call(() -> minable.contains(body.live().type(block.x(), block.y(), block.z())));
            if (still && mover.walk(Goal.reach(block), checkpoint) && miner.mine(block).confirmed()) {
                minedSinceScan++;
                body.main().sleep(10);
                pickup.collect(block, 4, checkpoint);
            }
        }
    }

    /** Hunts passive animals until the inventory holds {@code target} of the item. */
    public void hunt(Step.Hunt step, int target) {
        int explores = 0;
        while (count(step.item()) < target) {
            retreat.check();
            Optional<UUID> prey = body.main().call(() -> nearestPrey(step));
            if (prey.isEmpty()) {
                if (explores++ == MAX_EXPLORES) {
                    throw new ActionFailed("I found no animals that drop " + step.item().name());
                }
                explore(retreat);
                continue;
            }
            Pos fell = chase(prey.get());
            if (fell != null) {
                body.main().sleep(10);
                pickup.collect(fell, 5, retreat);
            }
        }
    }

    /** Walks a good distance in a random compass direction, to load fresh ground. */
    public void explore(Checkpoint checkpoint) {
        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int first = ThreadLocalRandom.current().nextInt(directions.length);
        for (int turn = 0; turn < directions.length; turn++) {
            int[] direction = directions[(first + turn) % directions.length];
            if (mover.walk(Goal.away(mover.feet(), direction[0], direction[1], EXPLORE_DISTANCE), checkpoint)) {
                return;
            }
        }
        throw new ActionFailed("I can't find a way out of here to explore");
    }

    private int count(ItemSet item) {
        return body.main().call(() -> Hands.count(body.player(), item));
    }

    /** The target blocks the helper's current tools will actually get drops from. */
    private Set<Material> harvestable(Set<Material> blocks) {
        List<ItemStack> tools = Hands.tools(body.player());
        Set<Material> minable = EnumSet.noneOf(Material.class);
        for (Material material : blocks) {
            BlockData data = material.createBlockData();
            if (!data.requiresCorrectToolForDrops() || tools.stream().anyMatch(data::isPreferredTool)) {
                minable.add(material);
            }
        }
        return minable;
    }

    private static Predicate<ItemStack> neededFor(Set<Material> blocks) {
        List<BlockData> picky = blocks.stream()
            .map(Material::createBlockData)
            .filter(BlockData::requiresCorrectToolForDrops)
            .toList();
        return stack -> !picky.isEmpty() && picky.stream().anyMatch(data -> data.isPreferredTool(stack));
    }

    private Deque<Pos> scan(Set<Material> wanted, Set<Pos> skipped) {
        Pos feet = mover.feet();
        WorldView view = WorldView.capture(body.main(), body.main().call(body::world),
            feet.x() - radius, feet.z() - radius, feet.x() + radius, feet.z() + radius);
        record Candidate(Pos pos, double score) {
        }
        List<Candidate> found = new ArrayList<>();
        long reach = (long) radius * radius;
        for (int x = feet.x() - radius; x <= feet.x() + radius; x++) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z++) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                if (dx * dx + dz * dz > reach || !view.covers(x, z)) {
                    continue;
                }
                for (int y = view.minY(); y < view.maxY(); y++) {
                    if (!wanted.contains(view.type(x, y, z))) {
                        continue;
                    }
                    Pos pos = new Pos(x, y, z);
                    if (skipped.contains(pos) || !body.policy().mayBreak(view, x, y, z)
                        || Digging.floodsIfOpened(view, x, y, z)) {
                        continue;
                    }
                    double score = Math.sqrt(pos.distanceSquared(feet)) + (exposed(view, pos) ? 0 : BURIED_PENALTY);
                    found.add(new Candidate(pos, score));
                }
            }
        }
        return found.stream()
            .sorted(Comparator.comparingDouble(Candidate::score))
            .limit(MAX_CANDIDATES)
            .map(Candidate::pos)
            .collect(Collectors.toCollection(ArrayDeque::new));
    }

    private static boolean exposed(WorldView view, Pos pos) {
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] side : sides) {
            if (Blocks.passable(view.type(pos.x() + side[0], pos.y() + side[1], pos.z() + side[2]))) {
                return true;
            }
        }
        return false;
    }

    private Optional<UUID> nearestPrey(Step.Hunt step) {
        Player player = body.player();
        return player.getWorld().getNearbyEntities(player.getLocation(), radius, radius / 2.0, radius).stream()
            .filter(entity -> step.mobs().contains(entity.getType()) && entity instanceof LivingEntity && !entity.isDead())
            .filter(entity -> !(entity instanceof Ageable ageable) || ageable.isAdult())
            .min(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(player.getLocation())))
            .map(Entity::getUniqueId);
    }

    /** Follows and hits the animal; returns where it fell, or null if it got away. */
    private Pos chase(UUID id) {
        for (int attempt = 0; attempt < MAX_CHASES; attempt++) {
            retreat.check();
            Location at = body.main().call(() -> Bukkit.getEntity(id) instanceof LivingEntity prey && !prey.isDead()
                ? prey.getLocation() : null);
            if (at == null) {
                return null;
            }
            boolean close = body.main().call(() -> body.player().getLocation().distance(at) <= HIT_RANGE);
            if (!close) {
                mover.walk(Goal.near(Pos.of(at), HIT_RANGE - 0.5), retreat);
                continue;
            }
            boolean killed = body.main().call(() -> {
                Player player = body.player();
                if (!(Bukkit.getEntity(id) instanceof LivingEntity prey) || player.getAttackCooldown() < 0.9f) {
                    return false;
                }
                Look.at(player, prey.getEyeLocation());
                player.attack(prey);
                player.swingMainHand();
                return prey.isDead();
            });
            if (killed) {
                return Pos.of(at);
            }
            body.main().sleep(4);
        }
        return null;
    }
}
