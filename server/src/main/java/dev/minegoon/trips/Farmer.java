package dev.minegoon.trips;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Look;
import dev.minegoon.actions.Miner;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Pickup;
import dev.minegoon.jobs.Step;
import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Vision;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;

/**
 * Grows a crop from a seed. Bone meal is what makes this finish in a trip
 * instead of half an hour of waiting on random ticks.
 */
public final class Farmer {

    private static final int MAX_FEEDS = 8;
    private static final Set<Material> SOIL = EnumSet.of(Material.DIRT, Material.GRASS_BLOCK, Material.FARMLAND);

    private final Body body;
    private final Mover mover;
    private final Miner miner;
    private final Pickup pickup;
    private final Retreat retreat;
    private final Vision vision;
    private final Prospector prospector;

    public Farmer(Body body, Mover mover, Miner miner, Pickup pickup, Retreat retreat, Vision vision,
                  Prospector prospector) {
        this.body = body;
        this.mover = mover;
        this.miner = miner;
        this.pickup = pickup;
        this.retreat = retreat;
        this.vision = vision;
        this.prospector = prospector;
    }

    public void grow(Step.Grow step, int target) {
        Prospector.Search search = prospector.search(step);
        while (count(step.crop()) < target) {
            retreat.check();
            Optional<Pos> soil = findSoil();
            if (soil.isEmpty()) {
                search.next(retreat);
                continue;
            }
            search.found();
            Pos plot = soil.get();
            if (!mover.walk(Goal.reach(plot), retreat)) {
                continue;
            }
            Pos crop = plant(step, plot);
            ripen(crop);
            if (!miner.mine(crop).confirmed()) {
                throw new ActionFailed("I couldn't harvest the " + step.crop().name());
            }
            pickup.collect(crop, 3, retreat);
        }
    }

    private Pos plant(Step.Grow step, Pos soil) {
        Pos above = soil.offset(0, 1, 0);
        Material cover = body.main().call(() -> body.live().type(above.x(), above.y(), above.z()));
        if (!cover.isAir() && Blocks.passable(cover)) {
            miner.mine(above);
        }
        Material ground = body.main().call(() -> body.live().type(soil.x(), soil.y(), soil.z()));
        if (ground != Material.FARMLAND) {
            if (!holdHoe() || !click(soil)) {
                throw new ActionFailed("I couldn't till the ground");
            }
        }
        if (!hold(step.seed()) || !click(soil)) {
            throw new ActionFailed("I couldn't plant " + ItemSet.key(step.seed()));
        }
        Material grew = body.main().call(() -> body.live().type(above.x(), above.y(), above.z()));
        if (grew != step.block()) {
            throw new ActionFailed("planting " + ItemSet.key(step.seed()) + " didn't leave a crop");
        }
        return above;
    }

    private void ripen(Pos crop) {
        int feeds = 0;
        while (feeds < MAX_FEEDS) {
            int age = age(crop);
            int max = maxAge(crop);
            if (age < 0) {
                throw new ActionFailed("the crop disappeared");
            }
            if (age >= max) {
                return;
            }
            if (!hold(Material.BONE_MEAL) || !click(crop) || age(crop) <= age) {
                throw new ActionFailed("I ran out of bone meal before the crop ripened");
            }
            feeds++;
        }
        throw new ActionFailed("the crop didn't ripen");
    }

    /** Open ground near the helper's own height, so the plot is a short walk and not down a cliff. */
    private Optional<Pos> findSoil() {
        int y = mover.feet().y();
        return vision.blocks(SOIL, 1, candidate -> {
            Pos pos = candidate.pos();
            return pos.y() >= y - 8 && pos.y() <= y + 4
                && Blocks.passable(candidate.view().type(pos.x(), pos.y() + 1, pos.z()))
                && body.policy().mayBreak(candidate.view(), pos.x(), pos.y(), pos.z());
        }).stream().findFirst();
    }

    private boolean holdHoe() {
        return body.main().call(() -> {
            for (Material hoe : Tag.ITEMS_HOES.getValues()) {
                if (Hands.hold(body.player(), hoe)) {
                    return true;
                }
            }
            return false;
        });
    }

    private boolean hold(Material material) {
        return body.main().call(() -> Hands.hold(body.player(), material));
    }

    private boolean click(Pos pos) {
        return body.main().call(() -> {
            Block block = pos.block(body.world());
            Look.at(body.player(), block.getLocation().add(0.5, 1, 0.5));
            return FakePlayer.useOn(body.player(), block);
        });
    }

    private int age(Pos crop) {
        return body.main().call(() -> body.live().data(crop.x(), crop.y(), crop.z()) instanceof Ageable age
            ? age.getAge() : -1);
    }

    private int maxAge(Pos crop) {
        return body.main().call(() -> body.live().data(crop.x(), crop.y(), crop.z()) instanceof Ageable age
            ? age.getMaximumAge() : 0);
    }

    private int count(ItemSet item) {
        return body.main().call(() -> Hands.count(body.player(), item));
    }
}
