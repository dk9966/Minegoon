package dev.minegoon.chat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a chat line into one command. "!get me a few diamonds" and "!get 5 diamond"
 * are the same get. Chat is what requires the leading !. This reader also accepts
 * the words without it, which is how the console passes a command through.
 *
 * <p>Read strictly, extra words are ignored. Chat only treats a line as a command
 * when it reads without that, so "!come look at this" and "!get logs and stone"
 * stay sentences. The helper's name is not an address.
 *
 * <p>Counts match the ones the model is told, so a sentence means the same thing
 * either way: "a few" is 3, "some" is 8, "a stack" is 64.
 */
final class Plain {

    record Order(String verb, int number, String item) {
    }

    private static final Set<String> LEADING = Set.of(
        "please", "hey", "hi", "hello", "ok", "okay", "um", "can", "could", "would", "will", "you", "just", "now");

    private static final Set<String> GET_SKIP = Set.of(
        "me", "us", "please", "the", "of", "my", "your", "to", "for", "just", "now");

    private static final Set<String> TAIL = Set.of(
        "here", "over", "now", "please", "me", "it", "that", "to", "the", "up", "back", "around", "nearby", "again");

    private static final Set<String> ZONE_SKIP = Set.of(
        "please", "me", "the", "this", "area", "nearby", "around", "here", "blocks", "block", "radius", "of");

    /** The brakes: they act at once and every time, never waiting on a model. */
    private static final Set<String> CONTROLS = Set.of("stop", "pause", "resume", "come");

    /** Any of these after watch asks for the view from behind: "watch in third person", "watch me from behind". */
    private static final Set<String> THIRD_PERSON = Set.of("third", "3rd", "behind", "chase");

    /** The small words that fill out "watch in third person" and "watch me from behind". */
    private static final Set<String> VIEW = Set.of("in", "person", "from", "view", "mode");

    /** Words that look like a get but are not an item, so a sentence is not stolen. */
    private static final Set<String> NOT_ITEM = Set.of("it", "that", "this", "them", "something", "stuff", "things");

    /** A get that says one of these is more than one item, so it stays a sentence. */
    private static final Set<String> MORE = Set.of("and", "then", "also", "plus", "but", "after");

    private Plain() {
    }

    /** strict ignores extra words. Chat uses the other way, so a sentence is not taken as a command. */
    static Optional<Order> read(String line, boolean strict) {
        List<String> words = words(line);
        int at = skipLeading(words);
        if (at >= words.size()) {
            return Optional.empty();
        }
        String verb = canonical(words.get(at));
        if (verb == null) {
            return Optional.empty();
        }
        List<String> rest = words.subList(at + 1, words.size());
        if (verb.equals("watch") && rest.stream().anyMatch(THIRD_PERSON::contains)) {
            if (strict || rest.stream().allMatch(word ->
                THIRD_PERSON.contains(word) || TAIL.contains(word) || VIEW.contains(word))) {
                return Optional.of(new Order("chase", 0, ""));
            }
            return Optional.empty();
        }
        return switch (verb) {
            case "get" -> readGet(rest, strict);
            case "protect", "allow" -> readZone(verb, rest, strict);
            default -> readSimple(verb, rest, strict);
        };
    }

    static boolean isControl(String line) {
        return read(line, false).map(order -> CONTROLS.contains(order.verb())).orElse(false);
    }

    private static Optional<Order> readGet(List<String> rest, boolean strict) {
        List<String> words = new ArrayList<>();
        for (String word : rest) {
            if (!GET_SKIP.contains(word)) {
                words.add(word);
            }
        }
        if (words.isEmpty()) {
            return Optional.of(new Order("get", 1, ""));
        }
        int count = 1;
        int start = 0;
        int end = words.size();
        int taken = quantityLength(words, 0);
        if (taken > 0) {
            count = quantity(words, 0, taken);
            start = taken;
        } else if (words.size() > 1 && isNumber(words.get(words.size() - 1))) {
            count = Integer.parseInt(words.get(words.size() - 1));
            end--;
        }
        List<String> item = words.subList(start, end);
        if (!strict && item.stream().anyMatch(word -> NOT_ITEM.contains(word) || MORE.contains(word))) {
            return Optional.empty();
        }
        return Optional.of(new Order("get", count, String.join(" ", item)));
    }

    private static Optional<Order> readZone(String verb, List<String> rest, boolean strict) {
        int radius = 0;
        for (String word : rest) {
            if (radius == 0 && isNumber(word)) {
                radius = Integer.parseInt(word);
            } else if (!strict && !ZONE_SKIP.contains(word)) {
                return Optional.empty();
            }
        }
        return Optional.of(new Order(verb, radius, ""));
    }

    private static Optional<Order> readSimple(String verb, List<String> rest, boolean strict) {
        if (!strict && rest.stream().anyMatch(word -> !TAIL.contains(word))) {
            return Optional.empty();
        }
        return Optional.of(new Order(verb, 0, ""));
    }

    /** grab, fetch, and bring are get. Everything else keeps its own word. */
    private static String canonical(String word) {
        return switch (word) {
            case "get", "grab", "fetch", "bring" -> "get";
            case "come", "give", "status", "inventory", "stop", "pause", "resume", "protect", "allow", "watch",
                "chase", "free", "back", "help", "plan" -> word;
            case "inv" -> "inventory";
            default -> null;
        };
    }

    private static int quantityLength(List<String> words, int at) {
        if (at >= words.size()) {
            return 0;
        }
        String word = words.get(at);
        if (isNumber(word) || word.equals("few") || word.equals("couple") || word.equals("some") || word.equals("stack")) {
            return 1;
        }
        if (word.equals("a") || word.equals("an") || word.equals("one")) {
            if (at + 1 < words.size()) {
                String next = words.get(at + 1);
                if (next.equals("few") || next.equals("couple") || next.equals("stack")) {
                    return 2;
                }
            }
            return 1;
        }
        return 0;
    }

    private static int quantity(List<String> words, int at, int taken) {
        String word = taken == 2 ? words.get(at + 1) : words.get(at);
        return switch (word) {
            case "few" -> 3;
            case "couple" -> 2;
            case "some" -> 8;
            case "stack" -> 64;
            case "a", "an", "one" -> 1;
            default -> Integer.parseInt(word);
        };
    }

    private static int skipLeading(List<String> words) {
        int i = 0;
        while (i < words.size() && LEADING.contains(words.get(i))) {
            i++;
        }
        return i;
    }

    private static List<String> words(String line) {
        String text = line.trim();
        if (text.startsWith("!")) {
            text = text.substring(1);
        }
        List<String> out = new ArrayList<>();
        for (String raw : text.trim().split("\\s+")) {
            String word = raw.replaceAll("^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "").toLowerCase(Locale.ROOT);
            if (!word.isEmpty()) {
                out.add(word);
            }
        }
        return out;
    }

    private static boolean isNumber(String word) {
        return word.matches("\\d{1,6}");
    }
}
