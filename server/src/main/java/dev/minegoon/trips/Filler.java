package dev.minegoon.trips;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Look;
import dev.minegoon.actions.Mover;
import dev.minegoon.jobs.Step;
import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Vision;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;

/** Fills buckets from water or lava. That is a right-click, not a recipe. */
public final class Filler {

    private static final int MAX_MISSES = 6;

    private final Body body;
    private final Mover mover;
    private final Retreat retreat;
    private final Vision vision;
    private final Prospector prospector;

    public Filler(Body body, Mover mover, Retreat retreat, Vision vision, Prospector prospector) {
        this.body = body;
        this.mover = mover;
        this.retreat = retreat;
        this.vision = vision;
        this.prospector = prospector;
    }

    public void fill(Step.Fill step, int target) {
        Prospector.Search search = prospector.search(step);
        Set<Pos> skipped = new HashSet<>();
        int misses = 0;
        while (count(step.item()) < target) {
            retreat.check();
            List<Pos> sources = vision.blocks(Set.of(step.fluid()), 1,
                candidate -> !skipped.contains(candidate.pos()) && search.reachable(candidate.pos())
                    && source(candidate.view().data(candidate.pos().x(), candidate.pos().y(), candidate.pos().z())));
            if (sources.isEmpty()) {
                search.next(retreat);
                continue;
            }
            Pos at = sources.getFirst();
            if (!mover.walk(Goal.reach(at), retreat)) {
                search.missed(at, retreat);
                continue;
            }
            boolean filled = body.main().call(() -> {
                if (!Hands.hold(body.player(), step.container())) {
                    return false;
                }
                Block block = at.block(body.world());
                Look.at(body.player(), block.getLocation().add(0.5, 0.5, 0.5));
                return FakePlayer.use(body.player());
            });
            if (filled) {
                search.found();
                vision.touched(at);
            } else {
                skipped.add(at);
                if (++misses >= MAX_MISSES) {
                    throw new ActionFailed("I couldn't fill the " + ItemSet.key(step.container()));
                }
            }
        }
    }

    /** A source block, not the flowing part, so the bucket actually fills. */
    private static boolean source(BlockData data) {
        return !(data instanceof Levelled level) || level.getLevel() == 0;
    }

    private int count(ItemSet item) {
        return body.main().call(() -> Hands.count(body.player(), item));
    }
}
