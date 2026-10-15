package dev.minegoon.trips;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.ActionFailed;
import dev.minegoon.actions.Body;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Miner;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Pickup;
import dev.minegoon.actions.Placer;
import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Pos;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Places a bed the helper is carrying and sleeps through the night, then
 * picks the bed back up. Monsters nearby keep a player awake, so it waits
 * while the reflexes deal with anything already in reach.
 */
public final class Sleeper {

    private static final int TRIES = 30;
    private static final int NIGHT_START = 12542;
    private static final int NIGHT_END = 23459;

    private static final int SHELTER_DEPTH = 2;
    private static final int PICKUP_TICKS = 20;

    private final Body body;
    private final Miner miner;
    private final Mover mover;
    private final Placer placer;
    private final Pickup pickup;

    public Sleeper(Body body, Miner miner, Mover mover, Placer placer, Pickup pickup) {
        this.body = body;
        this.miner = miner;
        this.mover = mover;
        this.placer = placer;
        this.pickup = pickup;
    }

    /**
     * Waits out the night dug in: two blocks down, with a block over the head
     * from what the digging dropped. What gets in anyway is fought on the checkpoint.
     */
    public void shelter(Checkpoint checkpoint) {
        if (!night()) {
            return;
        }
        for (int dug = 0; dug < SHELTER_DEPTH && mover.digDown(); dug++) {
            body.main().sleep(PICKUP_TICKS);
        }
        Pos lid = mover.settle().offset(0, 2, 0);
        body.main().run(() -> {
            if (Blocks.passable(lid.block(body.world()).getType())) {
                placer.placeAt(lid);
            }
        });
        while (night()) {
            checkpoint.check();
            body.main().sleep(20);
        }
    }

    public void sleep() {
        if (!night()) {
            return;
        }
        BedSpot spot = body.main().call(this::spot);
        if (spot == null) {
            throw new ActionFailed("no room to place a bed");
        }
        Pos bed = place(spot);
        boolean asleep = false;
        for (int attempt = 0; attempt < TRIES && !asleep; attempt++) {
            asleep = body.main().call(() -> {
                Player player = body.player();
                Block at = bed.block(player.getWorld());
                return Tag.BEDS.isTagged(at.getType()) && player.sleep(at.getLocation(), true);
            });
            if (!asleep) {
                body.main().sleep(10);
            }
        }
        if (!asleep) {
            throw new ActionFailed("I couldn't fall asleep");
        }
        for (int tick = 0; tick < 2400 && night(); tick++) {
            body.main().sleep(1);
        }
        if (miner.mine(bed).confirmed()) {
            pickup.collect(bed, 3, Checkpoint.NONE);
        }
    }

    public boolean nightOnSurface() {
        return body.main().call(() -> nightNow(body.player()) && body.player().getEyeLocation().getBlock().getLightFromSky() > 0);
    }

    public boolean night() {
        return body.main().call(() -> nightNow(body.player()));
    }

    public boolean hasBed() {
        return body.main().call(() -> holdingBed(body.player()));
    }

    private Pos place(BedSpot spot) {
        Pos placed = body.main().call(() -> {
            Player player = body.player();
            Material bed = bedItem(player);
            if (bed == null || !Hands.hold(player, bed)) {
                throw new ActionFailed("I have no bed");
            }
            FakePlayer.face(player, spot.yaw(), 45);
            Block ground = spot.ground().block(player.getWorld());
            if (!FakePlayer.useOn(player, ground)) {
                throw new ActionFailed("I couldn't place the bed");
            }
            Pos foot = spot.ground().offset(0, 1, 0);
            Block block = foot.block(player.getWorld());
            if (!Tag.BEDS.isTagged(block.getType())) {
                throw new ActionFailed("the bed didn't land");
            }
            body.policy().recordPlaced(block);
            Block head = foot.offset(spot.dx(), 0, spot.dz()).block(player.getWorld());
            if (Tag.BEDS.isTagged(head.getType())) {
                body.policy().recordPlaced(head);
            }
            return foot;
        });
        return placed;
    }

    /** Ground to click, and the direction the head of the bed should extend. */
    private BedSpot spot() {
        Player player = body.player();
        Pos feet = Pos.of(player.getLocation());
        int[][] directions = {{1, 0, -90}, {-1, 0, 90}, {0, 1, 0}, {0, -1, 180}};
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    Pos ground = feet.offset(dx, dy, dz);
                    if (ground.equals(feet) || ground.equals(feet.offset(0, -1, 0))) {
                        continue;
                    }
                    if (!Blocks.ground(ground.block(player.getWorld()).getType())) {
                        continue;
                    }
                    for (int[] direction : directions) {
                        Pos foot = ground.offset(0, 1, 0);
                        Pos head = foot.offset(direction[0], 0, direction[1]);
                        if (open(foot) && open(head)) {
                            return new BedSpot(ground, direction[0], direction[1], direction[2]);
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean open(Pos pos) {
        return Blocks.passable(body.live().type(pos.x(), pos.y(), pos.z()));
    }

    private static boolean holdingBed(Player player) {
        return bedItem(player) != null;
    }

    private static Material bedItem(Player player) {
        for (Material bed : Tag.ITEMS_BEDS.getValues()) {
            if (Hands.count(player, ItemSet.of(bed)) > 0) {
                return bed;
            }
        }
        return null;
    }

    private static boolean nightNow(Player player) {
        if (player == null) {
            return false;
        }
        long time = player.getWorld().getTime() % 24000;
        return time >= NIGHT_START && time <= NIGHT_END;
    }

    private record BedSpot(Pos ground, int dx, int dz, float yaw) {
    }
}
