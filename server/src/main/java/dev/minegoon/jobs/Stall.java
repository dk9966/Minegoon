package dev.minegoon.jobs;

/**
 * Keeps a long task honest. Progress is a job done or more of the goal in hand.
 * Every ten minutes the helper says what it is doing; after ten minutes without
 * progress it asks the model once for an idea; after thirty it gives up.
 */
final class Stall {

    static final long REPORT_MS = 10 * 60_000L;
    static final long IDEA_MS = 10 * 60_000L;
    static final long GIVE_UP_MS = 30 * 60_000L;

    enum Turn { NOTHING, REPORT, ASK, GIVE_UP }

    private long taskId = -1;
    private int done;
    private int have;
    private long progressAt;
    private long reportAt;
    private boolean asked;

    /** Nothing is running, so a paused task starts its clock over when it carries on. */
    synchronized void idle() {
        taskId = -1;
    }

    /** What to do now that {@code done} jobs are finished and {@code have} of the goal is in hand. */
    synchronized Turn check(long taskId, int done, int have, long now) {
        if (taskId != this.taskId) {
            this.taskId = taskId;
            progressAt = now;
            reportAt = now;
            asked = false;
        } else if (done > this.done || have > this.have) {
            progressAt = now;
            asked = false;
        }
        this.done = done;
        this.have = have;
        if (now - progressAt >= GIVE_UP_MS) {
            return Turn.GIVE_UP;
        }
        if (!asked && now - progressAt >= IDEA_MS) {
            asked = true;
            return Turn.ASK;
        }
        if (now - reportAt >= REPORT_MS) {
            reportAt = now;
            return Turn.REPORT;
        }
        return Turn.NOTHING;
    }
}
