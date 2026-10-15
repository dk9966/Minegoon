package dev.minegoon.world.path;

import dev.minegoon.world.Pos;

/**
 * What moving costs, in game ticks, from vanilla's movement speeds. The numbers
 * are the ones Baritone measured: walking is 4.317 blocks a second, sprinting
 * 5.612, and a fall follows gravity of 0.08 a tick with 2% drag.
 */
public final class Costs {

    public static final double WALK = 20 / 4.317;
    public static final double SPRINT = 20 / 5.612;
    public static final double WATER = 20 / 2.2;
    public static final double LADDER_UP = 20 / 2.35;
    public static final double LADDER_DOWN = 20 / 3.0;
    public static final double DIAGONAL = Math.sqrt(2);
    /** Rising one block takes this long; a jump peaks at 1.25 blocks. */
    public static final double JUMP = fall(1.25) - fall(0.25);
    /** Jumps are where a route goes wrong, so a flat way of the same length is preferred. */
    public static final double JUMP_PENALTY = 2;
    /** Stopping, turning to a face and placing: a block is worth spending only when it saves real time. */
    public static final double PLACE = 20;
    /** Breaking costs a little more than the dig itself: the block has to be faced and the drop waited for. */
    public static final double BREAK_EXTRA = 2;
    public static final double WALK_OFF_EDGE = WALK * 0.8;
    public static final double CENTER_AFTER_FALL = WALK * 0.2;
    /** Estimate per block of height, so a goal above or below pulls the search the right way. */
    private static final double UP = JUMP;
    private static final double DOWN = fall(1) + CENTER_AFTER_FALL / 2;

    private Costs() {
    }

    /** Ticks to fall {@code blocks} from rest. */
    public static double fall(double blocks) {
        double left = blocks;
        for (int tick = 1; ; tick++) {
            double speed = (Math.pow(0.98, tick) - 1) * -3.92;
            if (left <= speed) {
                return tick + left / speed;
            }
            left -= speed;
        }
    }

    /** A lower bound on the ticks from one feet position to another, sprinting diagonally where it can. */
    public static double estimate(Pos from, Pos to) {
        int dx = Math.abs(from.x() - to.x());
        int dz = Math.abs(from.z() - to.z());
        int diagonal = Math.min(dx, dz);
        int straight = Math.max(dx, dz) - diagonal;
        int dy = to.y() - from.y();
        return SPRINT * (straight + diagonal * DIAGONAL) + (dy > 0 ? dy * UP : -dy * DOWN);
    }
}
