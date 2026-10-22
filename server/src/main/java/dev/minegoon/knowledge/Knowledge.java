package dev.minegoon.knowledge;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.yaml.snakeyaml.Yaml;

/**
 * What the helper knows before it starts looking: at what height a block shows
 * up, which biomes have it, and which moves find it fastest. Read once from
 * knowledge.yml. Names are the game's keys, such as iron_ore, cow, or water.
 */
public final class Knowledge {

    /** A move that takes the helper somewhere worth looking from. */
    public enum Way {
        CLIFF, PEAK, CAVE, CAVERN, DIG, ROAM, VISIT;

        public static Optional<Way> parse(String name) {
            try {
                return Optional.of(valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A move a model may suggest. Digging blind is strip mining, which only knowledge.yml or a player asks for. */
        public boolean advisable() {
            return this != DIG;
        }
    }

    /** How to find one thing, or several merged. {@code best} is null when height does not matter. */
    public record Find(List<Way> ways, int low, int high, Integer best, List<String> biomes, List<String> visit,
                       String trip) {

        public static final Find ANYWHERE = new Find(List.of(), Integer.MIN_VALUE, Integer.MAX_VALUE, null,
            List.of(), List.of(), null);

        /** This first, then whatever {@code other} adds. */
        public Find then(Find other) {
            return new Find(union(ways, other.ways), Math.min(low, other.low), Math.max(high, other.high),
                best != null ? best : other.best, union(biomes, other.biomes), union(visit, other.visit),
                trip != null ? trip : other.trip);
        }

        private static <T> List<T> union(List<T> first, List<T> second) {
            Set<T> both = new LinkedHashSet<>(first);
            both.addAll(second);
            return List.copyOf(both);
        }
    }

    /**
     * Moves and biomes a player asked for, such as "strip mine" or "from the
     * plains". They go before lessons and knowledge, and they are the only way
     * dig, which is strip mining, gets into a search.
     */
    public record SearchOrders(List<Way> ways, List<String> biomes) {

        public static final SearchOrders NONE = new SearchOrders(List.of(), List.of());

        public SearchOrders {
            ways = List.copyOf(ways);
            biomes = List.copyOf(biomes);
        }

        public boolean none() {
            return ways.isEmpty() && biomes.isEmpty();
        }

        /** These orders first, then {@code base}, at its heights. Asking for a biome means going there. */
        public Find over(Find base) {
            if (none()) {
                return base;
            }
            List<Way> moves = biomes.isEmpty() || ways.contains(Way.ROAM) ? ways : Find.union(ways, List.of(Way.ROAM));
            return new Find(moves, base.low(), base.high(), base.best(), biomes, List.of(), base.trip()).then(base);
        }
    }

    /**
     * Carry {@code count} of any of {@code items} before the trip, plus
     * {@code each} more for every one of the thing the trip is after, so a trip
     * for 24 diamonds packs more food than a trip for one. Written as a number,
     * or as {@code [count, each]}. An optional supply, written with a trailing
     * {@code ?}, is only made when that is cheap.
     */
    public record Supply(List<String> items, int count, double each, boolean optional) {

        static Supply parse(String key, Object amount) {
            boolean optional = key.endsWith("?");
            String items = optional ? key.substring(0, key.length() - 1) : key;
            List<String> parts = strings(amount);
            int count = parts.isEmpty() ? number(amount) : Integer.parseInt(parts.get(0));
            double each = parts.size() > 1 ? Double.parseDouble(parts.get(1)) : 0;
            return new Supply(List.of(items.split("\\|")), count, each, optional);
        }

        /** How many to carry for a trip after {@code amount} of something. */
        public int needed(int amount) {
            return count + (int) Math.ceil(each * Math.max(0, amount));
        }
    }

    /** Blocks that give a structure away, and how much of them must be in sight together. */
    public record Structure(String name, int need, Map<String, Integer> marks) {
    }

    private final Map<String, Find> finds;
    private final Map<String, List<Supply>> trips;
    private final List<Structure> structures;
    private final List<String> problems;

    private Knowledge(Map<String, Find> finds, Map<String, List<Supply>> trips, List<Structure> structures,
                      List<String> problems) {
        this.finds = finds;
        this.trips = trips;
        this.structures = structures;
        this.problems = problems;
    }

    public static Knowledge read(Reader yaml) {
        Map<String, Object> root = map(new Yaml().load(yaml));
        List<String> problems = new ArrayList<>();
        Map<String, Find> finds = new LinkedHashMap<>();
        map(root.get("find")).forEach((name, entry) -> finds.put(name, find(name, map(entry), problems)));
        Map<String, List<Supply>> trips = new LinkedHashMap<>();
        map(root.get("trips")).forEach((name, entry) -> {
            List<Supply> supplies = new ArrayList<>();
            map(entry).forEach((items, amount) -> supplies.add(Supply.parse(items, amount)));
            trips.put(name, List.copyOf(supplies));
        });
        List<Structure> structures = new ArrayList<>();
        map(root.get("structures")).forEach((name, entry) -> {
            Map<String, Object> fields = map(entry);
            Map<String, Integer> marks = new LinkedHashMap<>();
            map(fields.get("marks")).forEach((block, weight) -> marks.put(block, number(weight)));
            structures.add(new Structure(name, number(fields.getOrDefault("need", 1)), Map.copyOf(marks)));
        });
        finds.values().stream()
            .filter(find -> find.trip() != null && !trips.containsKey(find.trip()))
            .forEach(find -> problems.add("unknown trip " + find.trip()));
        return new Knowledge(Map.copyOf(finds), Map.copyOf(trips), List.copyOf(structures), problems);
    }

    /** Everything known about these names, the first name's facts first. */
    public Find find(Collection<String> names) {
        Find merged = null;
        for (String name : names) {
            Find one = entry(name);
            if (one != null) {
                merged = merged == null ? one : merged.then(one);
            }
        }
        return merged == null ? Find.ANYWHERE : merged;
    }

    /** The file has an entry for this thing. */
    public boolean knows(String name) {
        return entry(name) != null;
    }

    /** The main entry name for a thing: deepslate ores share their stone version's entry. */
    public String canonical(String name) {
        if (finds.containsKey(name)) {
            return name;
        }
        String stone = name.startsWith("deepslate_") ? name.substring("deepslate_".length()) : name;
        return finds.containsKey(stone) ? stone : name;
    }

    public List<Supply> trip(String name) {
        return name == null ? List.of() : trips.getOrDefault(name, List.of());
    }

    public List<Structure> structures() {
        return structures;
    }

    public Set<String> structureNames() {
        Set<String> names = new LinkedHashSet<>();
        structures.forEach(structure -> names.add(structure.name()));
        return names;
    }

    /**
     * Names the game does not know, and moves that do not exist. Typos in the
     * file would otherwise quietly never match anything.
     */
    public List<String> problems(Predicate<String> knownBiome, Predicate<String> knownThing) {
        List<String> all = new ArrayList<>(problems);
        finds.forEach((name, find) -> {
            if (!knownThing.test(name)) {
                all.add("unknown block or animal " + name);
            }
            find.biomes().stream().filter(knownBiome.negate()).forEach(biome -> all.add(name + ": unknown biome " + biome));
            find.visit().stream().filter(structure -> !structureNames().contains(structure))
                .forEach(structure -> all.add(name + ": unknown structure " + structure));
        });
        trips.forEach((name, supplies) -> supplies.forEach(supply -> supply.items().stream()
            .filter(knownThing.negate()).forEach(item -> all.add("trip " + name + ": unknown item " + item))));
        structures.forEach(structure -> structure.marks().keySet().stream().filter(knownThing.negate())
            .forEach(block -> all.add(structure.name() + ": unknown block " + block)));
        return all;
    }

    private Find entry(String name) {
        return finds.get(canonical(name));
    }

    private static Find find(String name, Map<String, Object> fields, List<String> problems) {
        List<Way> ways = new ArrayList<>();
        for (String way : strings(fields.get("ways"))) {
            Way.parse(way).ifPresentOrElse(ways::add, () -> problems.add(name + ": unknown way " + way));
        }
        List<String> y = strings(fields.get("y"));
        int low = y.size() == 2 ? Integer.parseInt(y.get(0)) : Integer.MIN_VALUE;
        int high = y.size() == 2 ? Integer.parseInt(y.get(1)) : Integer.MAX_VALUE;
        Integer best = fields.containsKey("best") ? number(fields.get("best")) : null;
        Object trip = fields.get("trip");
        return new Find(List.copyOf(ways), low, high, best, strings(fields.get("biomes")), strings(fields.get("visit")),
            trip == null ? null : trip.toString());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        ((Map<Object, Object>) map).forEach((key, entry) -> copy.put(String.valueOf(key), entry));
        return copy;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    private static int number(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }
}
