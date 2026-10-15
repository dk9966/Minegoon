package dev.minegoon.world;

import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * What a route search needs to know about a block, packed into the bits of an
 * int so a search over many thousands of cells reads it with one lookup.
 * Worked out once per material and cached; safe to read from any thread.
 */
public final class Traits {

    /** The body can be inside it. */
    public static final int PASSABLE = 1;
    /** The body can stand on top of it. */
    public static final int STANDABLE = 1 << 1;
    public static final int WATER = 1 << 2;
    public static final int LAVA = 1 << 3;
    /** Hurts to be in or on: fire, cactus, magma, lava and the like. */
    public static final int HARMFUL = 1 << 4;
    /** Ladders, vines, scaffolding: the body climbs inside it. */
    public static final int CLIMBABLE = 1 << 5;
    /** Sand and gravel: falls when what is under it goes. */
    public static final int LOOSE = 1 << 6;
    /** A placed block can go here. */
    public static final int REPLACEABLE = 1 << 7;
    /** Unloaded or out of bounds. */
    public static final int UNKNOWN = 1 << 8;

    private static final int KNOWN = 1 << 30;
    private static final int[] TABLE = new int[Material.values().length];

    private Traits() {
    }

    public static int of(Material material) {
        int cached = TABLE[material.ordinal()];
        if (cached == 0) {
            cached = KNOWN | compute(material);
            TABLE[material.ordinal()] = cached;
        }
        return cached & ~KNOWN;
    }

    public static boolean has(int traits, int flag) {
        return (traits & flag) != 0;
    }

    private static int compute(Material material) {
        if (material == Material.BARRIER) {
            return UNKNOWN;
        }
        int traits = 0;
        traits |= Blocks.passable(material) ? PASSABLE : 0;
        traits |= Blocks.ground(material) ? STANDABLE : 0;
        traits |= Blocks.water(material) ? WATER : 0;
        traits |= material == Material.LAVA ? LAVA : 0;
        traits |= Blocks.harmful(material) ? HARMFUL : 0;
        traits |= Tag.CLIMBABLE.isTagged(material) ? CLIMBABLE : 0;
        traits |= material.hasGravity() ? LOOSE : 0;
        traits |= material.isAir() || Blocks.fluid(material) || Tag.REPLACEABLE.isTagged(material) ? REPLACEABLE : 0;
        return traits;
    }
}
