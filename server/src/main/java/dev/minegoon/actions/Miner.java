package dev.minegoon.actions;

import dev.minegoon.world.BlockReader;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/** Breaks one block the way a player does: in reach, with the best tool, taking the real time. */
public final class Miner {

    private static final int GIVE_UP_TICKS = 20 * 60;

    private final Body body;

    public Miner(Body body) {
        this.body = body;
    }

    public Receipt mine(Pos pos) {
        String action = "mine " + pos;
        Material target;
        try {
            target = body.main().call(() -> prepare(pos));
        } catch (ActionFailed refused) {
            return Receipt.refused(action, refused.getMessage());
        }
        if (target == null) {
            return Receipt.confirmed(action, "already clear");
        }
        double progress = 0;
        int ticks = 0;
        while (progress < 1) {
            double shown = progress;
            int tick = ticks;
            float speed = body.main().call(() -> hit(pos, target, shown, tick));
            if (speed < 0) {
                return Receipt.refused(action, "the block changed while mining");
            }
            progress += speed;
            if (++ticks > GIVE_UP_TICKS) {
                return Receipt.refused(action, "gave up after a minute on " + target.getKey().value());
            }
        }
        boolean cleared = body.main().call(() -> finish(pos));
        return cleared
            ? Receipt.confirmed(action, "broke " + target.getKey().value() + " in " + ticks + " ticks")
            : Receipt.refused(action, "the break of " + target.getKey().value() + " was cancelled");
    }

    /** Returns the block type to mine, null if there is nothing to mine, or throws if not allowed. */
    private Material prepare(Pos pos) {
        Player player = body.player();
        Block block = pos.block(player.getWorld());
        if (Blocks.passable(block.getType())) {
            return null;
        }
        BlockReader live = body.live();
        String name = block.getType().getKey().value();
        if (!body.policy().mayBreak(live, pos.x(), pos.y(), pos.z())) {
            throw new ActionFailed("not allowed to break " + name + " at " + pos);
        }
        if (Digging.floodsIfOpened(live, pos.x(), pos.y(), pos.z())) {
            throw new ActionFailed("liquid behind " + name + " at " + pos);
        }
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        if (player.getEyeLocation().distance(center) > Goal.REACH + 0.5) {
            throw new ActionFailed(name + " at " + pos + " is out of reach");
        }
        Hands.holdFastestFor(player, block.getBlockData());
        Look.at(player, center);
        return block.getType();
    }

    private float hit(Pos pos, Material target, double progress, int tick) {
        Player player = body.player();
        Block block = pos.block(player.getWorld());
        if (block.getType() != target) {
            return -1;
        }
        if (player.hasActiveItem()) {
            return 0;
        }
        if (tick % 4 == 0) {
            player.swingMainHand();
        }
        showCracks(player, block, (float) progress);
        return block.getBreakSpeed(player);
    }

    private boolean finish(Pos pos) {
        Player player = body.player();
        Block block = pos.block(player.getWorld());
        showCracks(player, block, 0);
        player.breakBlock(block);
        if (!Blocks.passable(block.getType())) {
            return false;
        }
        body.policy().forgetPlaced(block);
        return true;
    }

    private static void showCracks(Player helper, Block block, float progress) {
        Location location = block.getLocation();
        for (Player viewer : block.getWorld().getPlayers()) {
            if (viewer != helper && viewer.getLocation().distanceSquared(location) < 32 * 32) {
                viewer.sendBlockDamage(location, Math.min(progress, 1f), helper.getEntityId());
            }
        }
    }
}
