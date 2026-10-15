package dev.minegoon.world;

/**
 * The world as a route search sees it: the {@link Traits} of each block and what
 * breaking it would cost. Tests build one by hand; the game builds one from a
 * snapshot, so the search never touches live server state.
 */
public interface Terrain {

    int traits(int x, int y, int z);

    /** Ticks to break the block, or {@link Clearance#IMPOSSIBLE}. Only asked about blocks that are not passable. */
    double breakTicks(int x, int y, int z);

    /** The blocks themselves, for goals that look at them. Null for hand-built terrain. */
    BlockReader blocks();

    static Terrain of(BlockReader world, Clearance digging) {
        return new Terrain() {
            @Override
            public int traits(int x, int y, int z) {
                return Traits.of(world.type(x, y, z));
            }

            @Override
            public double breakTicks(int x, int y, int z) {
                return digging.cost(world, x, y, z);
            }

            @Override
            public BlockReader blocks() {
                return world;
            }
        };
    }
}
