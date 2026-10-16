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
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Vision;
import dev.minegoon.world.WorldView;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Trips out into the world: finding blocks or animals that drop what a job
 * needs, walking to them, and collecting the drops. What it looks for is
 * whatever is in sight; when nothing is, the prospector takes it somewhere
 * better to look from.
 */
public final class Gatherer {

    private static final int MAX_CANDIDATES = 64;
    private static final int RESCAN_EVERY = 8;
    private static final double HIT_RANGE = 2.8;
    private static final int MAX_CHASES = 24;
    private static final int FED = Retreat.REGENERATES_AT;
    private static final int BITE_TICKS = 40;
    public static final Set<EntityType> MEALS = EnumSet.of(
        EntityType.COW, EntityType.PIG, EntityType.SHEEP, EntityType.CHICKEN);

    private final Body body;
    private final Mover mover;
    private final Miner miner;
    private final Pickup pickup;
    private final Retreat retreat;
    private final Vision vision;
    private final Prospector prospector;

    public Gatherer(Body body, Mover mover, Miner miner, Pickup pickup, Retreat retreat, Vision vision,
                    Prospector prospector) {
        this.body = body;
        this.mover = mover;
        this.miner = miner;
        this.pickup = pickup;
        this.retreat = retreat;
        this.vision = vision;
        this.prospector = prospector;
    }

    /** Mines until the inventory holds {@code target} of the item. */
    public void gather(Step.Gather step, int target) {
        Set<Material> minable = body.main().call(() -> harvestable(step.blocks()));
        if (minable.isEmpty()) {
            throw new ActionFailed("I don't have a tool that can harvest " + ItemSet.of(step.blocks()).name());
        }
        Deque<Pos> candidates = new ArrayDeque<>();
        Checkpoint checkpoint = retreat.guarding(neededFor(minable), () -> !candidates.isEmpty());
        Prospector.Search search = prospector.search(step);
        Set<Pos> skipped = new HashSet<>();
        int minedSinceLook = 0;
        while (count(step.item()) < target) {
            checkpoint.check();
            if (candidates.isEmpty() || minedSinceLook == RESCAN_EVERY) {
                candidates.clear();
                candidates.addAll(vision.blocks(minable, MAX_CANDIDATES,
                    takeable(skipped, step.ripe()).and(candidate -> search.reachable(candidate.pos()))));
                minedSinceLook = 0;
            }
            if (candidates.isEmpty()) {
                search.next(checkpoint);
                continue;
            }
            Pos block = candidates.poll();
            skipped.add(block);
            boolean still = body.main().call(() -> minable.contains(body.live().type(block.x(), block.y(), block.z())));
            boolean walked = still && mover.walk(Goal.reach(block), checkpoint);
            if (still && !walked) {
                search.missed(block, checkpoint);
                candidates.clear();
                continue;
            }
            if (walked && miner.mine(block).confirmed()) {
                minedSinceLook++;
                search.found();
                vision.touched(block);
                body.main().sleep(10);
                pickup.collect(block, 4, checkpoint);
            }
        }
    }

    /**
     * Eats until health can come back, hunting a cow, pig, sheep, or chicken
     * whenever there is nothing left to eat. Stops when the helper is fed, not
     * when the meat is still sitting in the inventory.
     */
    public void forage() {
        Checkpoint checkpoint = retreat.ignoringHunger();
        Prospector.Search search = prospector.search("animals to eat", MEALS.stream().map(Prospector::key).toList());
        while (foodLevel() < FED) {
            checkpoint.check();
            if (body.main().call(() -> Food.any(body.player()) && Reflexes.eat(body.player(), FED))) {
                body.main().sleep(BITE_TICKS);
                continue;
            }
            List<UUID> prey = vision.creatures(MEALS, entity -> true, 1);
            if (prey.isEmpty()) {
                search.next(checkpoint);
                continue;
            }
            Pos fell = chase(prey.getFirst(), checkpoint);
            if (fell != null) {
                search.found();
                body.main().sleep(10);
                pickup.collect(fell, 5, checkpoint);
            }
        }
    }

    /** The tool a gather is about to break, or null when the helper can keep digging. */
    public Material replacementFor(Set<Material> blocks) {
        return retreat.wornTool(neededFor(blocks));
    }

    /** Hunts animals until the inventory holds {@code target} of the item. */
    public void hunt(Step.Hunt step, int target) {
        Prospector.Search search = prospector.search(step);
        while (count(step.item()) < target) {
            retreat.check();
            List<UUID> prey = vision.creatures(step.mobs(), dropping(step.item()), 1);
            if (prey.isEmpty()) {
                search.next(retreat);
                continue;
            }
            Pos fell = chase(prey.getFirst(), retreat);
            if (fell != null) {
                search.found();
                body.main().sleep(10);
                pickup.collect(fell, 5, retreat);
            }
        }
    }

    /** A sheep drops wool of its own colour only, and none once sheared. */
    private static Predicate<Entity> dropping(ItemSet items) {
        boolean wool = items.members().stream().anyMatch(Tag.WOOL::isTagged);
        return entity -> !wool || !(entity instanceof Sheep sheep)
            || !sheep.isSheared() && items.contains(Material.matchMaterial(sheep.getColor().name() + "_WOOL"));
    }

    /** How many matching blocks are in sight from here, capped at the search limit. */
    public int visible(Set<Material> wanted, boolean ripe) {
        return vision.blocks(wanted, MAX_CANDIDATES, takeable(Set.of(), ripe)).size();
    }

    private Predicate<Vision.Candidate> takeable(Set<Pos> skipped, boolean ripe) {
        return candidate -> {
            Pos pos = candidate.pos();
            WorldView view = candidate.view();
            return !skipped.contains(pos)
                && (!ripe || mature(view, pos))
                && body.policy().mayBreak(view, pos.x(), pos.y(), pos.z())
                && !Digging.floodsIfOpened(view, pos.x(), pos.y(), pos.z());
        };
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

    private int foodLevel() {
        return body.main().call(() -> body.player().getFoodLevel());
    }

    private static boolean mature(WorldView view, Pos pos) {
        return view.data(pos.x(), pos.y(), pos.z()) instanceof org.bukkit.block.data.Ageable age
            && age.getAge() >= age.getMaximumAge();
    }

    /** Follows and hits the animal; returns where it fell, or null if it got away. */
    private Pos chase(UUID id, Checkpoint checkpoint) {
        for (int attempt = 0; attempt < MAX_CHASES; attempt++) {
            checkpoint.check();
            Location at = body.main().call(() -> Bukkit.getEntity(id) instanceof LivingEntity prey && !prey.isDead()
                ? prey.getLocation() : null);
            if (at == null) {
                return null;
            }
            boolean close = body.main().call(() -> body.player().getLocation().distance(at) <= HIT_RANGE);
            if (!close) {
                mover.walk(Goal.near(Pos.of(at), HIT_RANGE - 0.5), checkpoint);
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
