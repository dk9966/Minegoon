package dev.minegoon.actions;

/** Called between steps of long actions so a trip can end early, for example to retreat. */
@FunctionalInterface
public interface Checkpoint {

    Checkpoint NONE = () -> { };

    void check();
}
