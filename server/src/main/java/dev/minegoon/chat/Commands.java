package dev.minegoon.chat;

import dev.minegoon.ItemSet;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Request;
import dev.minegoon.safety.Zones;
import dev.minegoon.spawn.Helper;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * Commands from a ! line, from the model, or from the console. Answers come
 * back as the helper's chat. A line with no ! is not a command.
 */
public final class Commands {

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 128;
    public static final int MAX_COUNT = 64 * 9;
    private static final List<String> HELP = List.of(
        "Start a chat line with ! when you want me. I ignore every other line.",
        "!help shows this list.",
        "!get <count> <item> fetches one item and brings it to you. !get 5 diamond, !get 64 oak_log, !get 1 bucket.",
        "!get me a few diamonds is the same. a few is 3, a couple is 2, some is 8, a stack is 64. grab, fetch, and bring mean get.",
        "!come walks to you. !give walks to you and drops what the last task gathered.",
        "!status says what I'm doing and how far along. !plan says the steps of the current job.",
        "!inventory, or !inv, opens a live look-only view of what I'm carrying. Clicks change nothing.",
        "!stop drops all work. !pause pauses it. !resume carries on. These run at once.",
        "!protect [radius] means I never dig within that many blocks of where you're standing. Leave the number off for 16. 128 is the most.",
        "!allow [radius] lets me dig player-made blocks within that many blocks of you, for the current task only. Say it after a task. Leave the number off for 16. Protected ground stays untouched.",
        "!watch lets you see through my eyes, in spectator. !chase follows from behind and above, like third person.",
        "!watch in third person means !chase. !free stops following and stays in spectator where the camera is. Sneaking while you follow does that too.",
        "!back stops watching and puts you back where you were, in the game mode you had.",
        "Anything else after ! is a sentence, when a key is set.",
        "!what are you carrying? !what's around you? !how is the work going? !I need a bucket and 16 torches, then come back.",
        "I answer, look, fetch one item, or take on the longer job. While I'm already on one, a sentence changes it: !bring a bucket too.",
        "Without a key, only the commands above run.");

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

    /** One command, such as "!get me 5 diamonds" or "!come here". A longer sentence is not. */
    public boolean understands(String line) {
        return Plain.read(line, false).isPresent();
    }

    /** What to say when a ! line is not a command and there is no key to read a sentence. */
    public void missed() {
        helper.say("That isn't one of my commands. Say !help for the list. A full sentence needs a key in config.yml.");
    }

    public void handle(Speaker speaker, String line) {
        boolean bang = line.trim().startsWith("!");
        Plain.read(line, bang).ifPresentOrElse(
            order -> dispatch(speaker, order),
            () -> {
                if (bang) {
                    help();
                }
            });
    }

    private void dispatch(Speaker speaker, Plain.Order order) {
        switch (order.verb()) {
            case "get" -> get(speaker, order.number(), order.item());
            case "come" -> foreman.come(speaker.id());
            case "give" -> foreman.give(speaker.id());
            case "status" -> foreman.status().forEach(helper::say);
            case "plan" -> foreman.plan().forEach(helper::say);
            case "inventory" -> peek.open(speaker.id());
            case "stop" -> foreman.stop();
            case "pause" -> foreman.pause();
            case "resume" -> foreman.resume();
            case "protect" -> protect(speaker, order.number());
            case "allow" -> allow(speaker, order.number());
            case "watch" -> watch.start(speaker.id());
            case "chase" -> watch.chase(speaker.id());
            case "free" -> watch.free(speaker.id());
            case "back" -> watch.stop(speaker.id());
            case "help" -> help();
            default -> help();
        }
    }

    private void help() {
        HELP.forEach(helper::say);
    }

    private void get(Speaker speaker, int count, String item) {
        Material goal = item.isBlank() ? null : ItemSet.parse(item);
        if (goal == null) {
            helper.say(item.isBlank() ? "Get what? Try !get 5 diamond." : "I don't know an item called " + item + ".");
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
