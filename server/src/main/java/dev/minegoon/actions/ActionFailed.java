package dev.minegoon.actions;

/** The world did not do what an action needed. The message is shown to players and models. */
public class ActionFailed extends RuntimeException {

    public ActionFailed(String reason) {
        super(reason, null, false, false);
    }
}
