package dev.minegoon.world.path;

/** The kinds of step a route is made of. Each has its own cost rule and its own way of being driven. */
public enum Move {
    /** One block along a compass direction, on the same level. Places a floor first when there is none. */
    TRAVERSE,
    /** One block diagonally, on the same level, between two corners the body fits past. */
    DIAGONAL,
    /** Across the surface of deep water with the head out. */
    SWIM,
    /** Jumps up one block onto the next column. Places the block to land on when there is none. */
    ASCEND,
    /** Walks off one block down onto the next column. */
    DESCEND,
    /** Walks off an edge and drops two blocks or more, onto ground or into water. */
    FALL,
    /** Jumps straight up and places a block underneath. */
    PILLAR,
    /** Up a ladder or vine. */
    CLIMB_UP,
    /** Down a ladder or vine. */
    CLIMB_DOWN,
    /** Breaks the block underfoot and drops onto the one below it. */
    DOWNWARD,
    /** A running jump over a gap, landing level or one block up. */
    PARKOUR;

    /** Moves that change the column the body is in, so the executor can keep sprinting through them. */
    public boolean horizontal() {
        return this != PILLAR && this != CLIMB_UP && this != CLIMB_DOWN && this != DOWNWARD;
    }
}
