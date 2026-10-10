package dev.minegoon.world;

/** What it takes to make a block passable for the body. */
@FunctionalInterface
public interface Clearance {

    double IMPOSSIBLE = Double.POSITIVE_INFINITY;

    /** Zero when the body can already pass, the ticks needed to dig it out, or {@link #IMPOSSIBLE}. */
    double cost(BlockReader world, int x, int y, int z);
}
