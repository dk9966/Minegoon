package dev.minegoon.chat;

import dev.minegoon.ItemSet;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.safety.Zones;
import dev.minegoon.spawn.Helper;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * The ! commands, whether typed in chat, produced from free text by the
 * model, or sent from the console. Answers come back as the helper's chat.
 */
public final class Commands {

    private static final int DEFAULT_RADIUS = 16;
    private static final int MAX_RADIUS = 128;
    private static final int MAX_COUNT = 64 * 9;
    private static final String HELP = "Try !get 5 diamond, !come, !give, !status, !stop, !pause, !resume, "
        + "!protect 20, or !allow 10.";

    /** Who asked, and where they were standing. Console requests have no id. */
    public record Speaker(UUID id, String name, Location location) {
    }

    private final Helper helper;
    private final Foreman foreman;
    private final Zones zones;

    public Commands(Helper helper, Foreman foreman, Zones zones) {
        this.helper = helper;
        this.foreman = foreman;
        this.zones = zones;
    }

    public void handle(Speaker speaker, String line) {
        String[] words = line.trim().substring(1).split("\\s+");
        String[] rest = Arrays.copyOfRange(words, 1, words.length);
        switch (words[0].toLowerCase(Locale.ROOT)) {
            case "get" -> get(speaker, rest);
            case "come" -> foreman.come(speaker.id());
            case "give" -> foreman.give(speaker.id());
            case "status" -> foreman.status().forEach(helper::say);
            case "stop" -> foreman.stop();
            case "pause" -> foreman.pause();
            case "resume" -> foreman.resume();
            case "protect" -> protect(speaker, rest);
            case "allow" -> allow(speaker, rest);
            default -> helper.say(HELP);
        }
    }

    /** Accepts "!get 5 diamond", "!get diamond 5", and "!get oak log". */
    private void get(Speaker speaker, String[] words) {
        int count = 1;
        String item = String.join(" ", words);
        if (words.length > 1 && isNumber(words[0])) {
            count = Integer.parseInt(words[0]);
            item = String.join(" ", Arrays.copyOfRange(words, 1, words.length));
        } else if (words.length > 1 && isNumber(words[words.length - 1])) {
            count = Integer.parseInt(words[words.length - 1]);
            item = String.join(" ", Arrays.copyOfRange(words, 0, words.length - 1));
        }
        Material goal = item.isBlank() ? null : ItemSet.parse(item);
        if (goal == null) {
            helper.say(item.isBlank() ? "Get what? Try !get 5 diamond." : "I don't know an item called " + item + ".");
            return;
        }
        if (count < 1 || count > MAX_COUNT) {
            helper.say("I can fetch between 1 and " + MAX_COUNT + " at a time.");
            return;
        }
        foreman.get(speaker.id(), speaker.name(), speaker.location(), goal, count);
    }

    private void protect(Speaker speaker, String[] words) {
        int radius = radius(words);
        Location at = speaker.location();
        zones.add(Zones.Kind.PROTECT, at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), radius, null);
        helper.say("Got it. I won't break anything within " + radius + " blocks of " + coordinates(at) + ".");
    }

    private void allow(Speaker speaker, String[] words) {
        foreman.current().ifPresentOrElse(task -> {
            int radius = radius(words);
            Location at = speaker.location();
            zones.add(Zones.Kind.ALLOW, at.getWorld().getName(), at.getBlockX(), at.getBlockZ(), radius, task.id());
            helper.say("Okay, for this task I may dig player-made blocks within " + radius + " blocks of "
                + coordinates(at) + ". Protected areas still stay untouched.");
        }, () -> helper.say("Give me a task first, then !allow applies to it."));
    }

    private static int radius(String[] words) {
        int radius = words.length > 0 && isNumber(words[0]) ? Integer.parseInt(words[0]) : DEFAULT_RADIUS;
        return Math.clamp(radius, 1, MAX_RADIUS);
    }

    private static boolean isNumber(String word) {
        return word.matches("\\d{1,6}");
    }

    private static String coordinates(Location at) {
        return at.getBlockX() + " " + at.getBlockZ();
    }
}
