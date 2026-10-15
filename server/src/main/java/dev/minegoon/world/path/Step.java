package dev.minegoon.world.path;

import dev.minegoon.world.Pos;
import java.util.List;

/**
 * One move of a route, between two feet positions, with the ticks the search
 * expected it to take. {@code places}: the move puts down the block it ends up standing on.
 */
public record Step(Move move, Pos from, Pos to, double cost, boolean places) {

    public int dx() {
        return Integer.signum(to.x() - from.x());
    }

    public int dz() {
        return Integer.signum(to.z() - from.z());
    }

    /**
     * Feet positions the body passes through on the way, so the executor can tell
     * a step in progress from one that went wrong.
     */
    public boolean allows(Pos feet) {
        if (feet.equals(from) || feet.equals(to)) {
            return true;
        }
        return switch (move) {
            case TRAVERSE, SWIM -> false;
            case DIAGONAL -> feet.y() == from.y()
                && (feet.x() == from.x() && feet.z() == to.z() || feet.x() == to.x() && feet.z() == from.z());
            case ASCEND, PILLAR, CLIMB_UP -> feet.equals(from.offset(0, 1, 0));
            case DESCEND, FALL -> feet.x() == to.x() && feet.z() == to.z() && feet.y() > to.y() && feet.y() <= from.y();
            case CLIMB_DOWN, DOWNWARD -> false;
            case PARKOUR -> inLine(feet) && feet.y() >= from.y() && feet.y() <= from.y() + 1;
        };
    }

    /** Cells to break before moving, head height first so nothing drops onto the body. */
    public List<Pos> clears() {
        Pos high = new Pos(to.x(), from.y() + 1, to.z());
        Pos level = new Pos(to.x(), from.y(), to.z());
        return switch (move) {
            case TRAVERSE, SWIM -> List.of(to.offset(0, 1, 0), to);
            case ASCEND -> List.of(from.offset(0, 2, 0), to.offset(0, 1, 0), to);
            case DESCEND -> List.of(high, level, to);
            case FALL -> List.of(high, level);
            case PILLAR, CLIMB_UP -> List.of(to.offset(0, 1, 0));
            case DOWNWARD -> List.of(to);
            case DIAGONAL, PARKOUR, CLIMB_DOWN -> List.of();
        };
    }

    /** The block the move places to stand on, or null. */
    public Pos floor() {
        if (!places) {
            return null;
        }
        return move == Move.PILLAR ? from : to.offset(0, -1, 0);
    }

    private boolean inLine(Pos feet) {
        int along = (feet.x() - from.x()) * dx() + (feet.z() - from.z()) * dz();
        int across = (feet.x() - from.x()) * dz() - (feet.z() - from.z()) * dx();
        int length = Math.abs(to.x() - from.x()) + Math.abs(to.z() - from.z());
        return across == 0 && along >= 0 && along <= length;
    }
}
