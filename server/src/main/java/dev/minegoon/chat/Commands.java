package dev.minegoon.chat;

import dev.minegoon.ItemSet;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Request;
import dev.minegoon.safety.Zones;
import dev.minegoon.spawn.Helper;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Commands from chat, from a plain sentence, from the model, or from the
 * console. Answers come back as the helper's chat.
 */
public final class Commands {

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 128;
    public static final int MAX_COUNT = 64 * 9;
    private static final String HELP = "Say get 5 diamond, come, give, status, inventory, stop, pause, "
        + "resume, protect 20, allow 10, watch, or back.";

    /** Who asked, and where they were standing. Console requests have no id. */
    public record Speaker(UUID id, String name, Location location) {
    }

    private final Helper helper;
    private final Foreman foreman;
    private final Zones zones;
    private final Watch watch;
    private final Peek peek;

    public Commands(Helper helper, Foreman foreman, Zones zones, Watch watch, Peek peek) {
        this.helper = helper;
        this.foreman = foreman;
        this.zones = zones;
        this.watch = watch;
        this.peek = peek;
    }

    /** A plain sentence such as "get me 5 diamonds", with no ! and no model. */
    public boolean understands(String line) {
        return Plain.read(line, helper.name(), false).isPresent();
    }

    public void handle(Speaker speaker, String line) {
        boolean bang = line.trim().startsWith("!");
        Plain.read(line, helper.name(), bang).ifPresentOrElse(
            order -> dispatch(speaker, order),
            () -> {
                if (bang) {
                    helper.say(HELP);
                }
            });
    }

    private void dispatch(Speaker speaker, Plain.Order order) {
        switch (order.verb()) {
            case "get" -> get(speaker, order.number(), order.item());
            case "come" -> foreman.come(speaker.id());
            case "give" -> foreman.give(speaker.id());
            case "status" -> foreman.status().forEach(helper::say);
            case "inventory" -> peek.open(speaker.id());
            case "stop" -> foreman.stop();
            case "pause" -> foreman.pause();
            case "resume" -> foreman.resume();
            case "protect" -> protect(speaker, order.number());
            case "allow" -> allow(speaker, order.number());
            case "watch" -> watch.start(speaker.id());
            case "back" -> watch.stop(speaker.id());
            case "help" -> helper.say(HELP);
            default -> helper.say(HELP);
        }
    }

    private void get(Speaker speaker, int count, String item) {
        Material goal = item.isBlank() ? null : ItemSet.parse(item);
        if (goal == null) {
            helper.say(item.isBlank() ? "Get what? Try get 5 diamond." : "I don't know an item called " + item + ".");
            return;
        }
        if (count < 1 || count > MAX_COUNT) {
            helper.say("I can fetch between 1 and " + MAX_COUNT + " at a time.");
            return;
        }
        foreman.get(Request.of(speaker.id(), speaker.name(), speaker.location(), goal, count));
    }

    private void protect(Speaker speaker, int given) {
        int radius = radius(given);
        Location at = speaker.location();
        zones.add(Zones.Kind.PROTECT, at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), radius, null);
        helper.say("Got it. I won't break anything within " + radius + " blocks of " + coordinates(at) + ".");
    }

    private void allow(Speaker speaker, int given) {
        foreman.current().ifPresentOrElse(task -> {
            int radius = radius(given);
            Location at = speaker.location();
            zones.add(Zones.Kind.ALLOW, at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), radius, task.id());
            helper.say("Okay, for this task I may dig player-made blocks within " + radius + " blocks of "
                + coordinates(at) + ". Protected areas still stay untouched.");
        }, () -> helper.say("Give me a task first, then allow applies to it."));
    }

    /** Zero means the speaker left the radius off, so use the default. */
    private static int radius(int given) {
        return given == 0 ? DEFAULT_RADIUS : Math.clamp(given, 1, MAX_RADIUS);
    }

    private static String coordinates(Location at) {
        return at.getBlockX() + " " + at.getBlockZ();
    }
}
