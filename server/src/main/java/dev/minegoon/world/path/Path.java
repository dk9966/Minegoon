package dev.minegoon.world.path;

import dev.minegoon.world.Pos;
import java.util.List;

/** Steps from start to end. {@code complete} is false when the search only got part of the way. */
public record Path(Pos start, List<Step> steps, boolean complete) {

    public Pos end() {
        return steps.isEmpty() ? start : steps.getLast().to();
    }

    public double cost() {
        return steps.stream().mapToDouble(Step::cost).sum();
    }
}
