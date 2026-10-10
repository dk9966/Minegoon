package dev.minegoon.jobs;

import java.util.Objects;

/**
 * One step of a task on the board. {@code baseline} is the counted item's
 * amount when the job first started, so progress survives retries and restarts.
 */
public record Job(long id, long taskId, int position, Step step, Status status, int attempts, Integer baseline,
                  String lastError) {

    public enum Status { OPEN, ACTIVE, DONE, BLOCKED }

    /** Same kind of step for the same item, even if the amounts changed in a replan. */
    public boolean sameWorkAs(Step other) {
        return step.getClass() == other.getClass() && Objects.equals(step.counted(), other.counted());
    }
}
