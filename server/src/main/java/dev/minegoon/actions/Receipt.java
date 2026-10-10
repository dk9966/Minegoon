package dev.minegoon.actions;

/** What an action asked the world to do and what the world was then seen to do. */
public record Receipt(String action, boolean confirmed, String observed) {

    public static Receipt confirmed(String action, String observed) {
        return new Receipt(action, true, observed);
    }

    public static Receipt refused(String action, String observed) {
        return new Receipt(action, false, observed);
    }

    /** Turns an unconfirmed receipt into a failure of the current step. */
    public Receipt orFail() {
        if (!confirmed) {
            throw new ActionFailed(action + ": " + observed);
        }
        return this;
    }

    @Override
    public String toString() {
        return (confirmed ? "ok " : "failed ") + action + ": " + observed;
    }
}
