package dev.minegoon.world.path;

/**
 * What the body brings to a route besides its legs: blocks it may place for
 * bridges and pillars, a water bucket that makes a long drop safe, and whether
 * the route is priced at a sprint. The helper sprints even when hungry.
 */
public record Kit(int blocks, boolean bucket, boolean sprint) {

    public static final Kit BARE = new Kit(0, false, true);
}
