package dev.minegoon.planner;

import dev.minegoon.ItemSet;
import dev.minegoon.jobs.Step;
import dev.minegoon.planner.Cookbook.Craft;
import dev.minegoon.planner.Cookbook.Hunt;
import dev.minegoon.planner.Cookbook.Ingredient;
import dev.minegoon.planner.Cookbook.Mine;
import dev.minegoon.planner.Cookbook.Smelt;
import dev.minegoon.planner.Cookbook.Way;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Turns "5 diamond" into an ordered list of steps. It works backwards from the
 * goal, uses what is already in the inventory first, adds the tools and
 * stations each step needs, and merges repeated needs into one step. A step
 * always comes after everything it depends on.
 */
public final class Planner {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_TRIES = 5_000;
    private static final ItemSet TABLE = ItemSet.of(Material.CRAFTING_TABLE);
    private static final ItemSet FURNACE = ItemSet.of(Material.FURNACE);
    private static final ItemSet COAL = ItemSet.of(Set.of(Material.COAL, Material.CHARCOAL));
    private static final ItemSet PLANKS = ItemSet.of(Tag.PLANKS.getValues());
    private static final int SMELTS_PER_COAL = 8;
    private static final double SMELTS_PER_PLANK = 1.5;

    private final Cookbook book;

    public Planner(Cookbook book) {
        this.book = book;
    }

    public static final class Unplannable extends Exception {
        Unplannable(String reason) {
            super(reason);
        }
    }

    /** The cookbook ways a step stands for, so a replan can avoid them. */
    public static Set<String> waysBehind(Step step) {
        String way = switch (step) {
            case Step.Gather gather -> "mine";
            case Step.Hunt hunt -> "hunt";
            case Step.Craft craft -> "craft";
            case Step.Smelt smelt -> "smelt";
            case Step.Deliver deliver -> null;
            case Step.Recover recover -> null;
        };
        if (way == null) {
            return Set.of();
        }
        return step.counted().members().stream()
            .map(item -> way + ":" + ItemSet.key(item))
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Ends with handing the goal items over. */
    public List<Step> plan(ItemSet goal, int count, Map<Material, Integer> inventory, Set<String> avoid)
            throws Unplannable {
        Session session = new Session(inventory, avoid);
        session.need(goal, count, 0);
        List<Step> steps = new ArrayList<>(session.steps());
        steps.add(new Step.Deliver(goal, count));
        return steps;
    }

    /**
     * One planning attempt. Ways are tried best first; a way that fails, for
     * example because it needs the very tool being made (diamonds for a diamond
     * pickaxe), is undone and the next one is tried.
     */
    private final class Session {
        private final Set<String> avoid;
        private final Set<ItemSet> making = new HashSet<>();
        private final Map<String, Pending> steps = new LinkedHashMap<>();
        private Stock stock;
        private int tries;

        Session(Map<Material, Integer> inventory, Set<String> avoid) {
            this.stock = new Stock(inventory);
            this.avoid = avoid;
        }

        private record Saved(Stock stock, Map<String, Integer> amounts) {
        }

        void need(ItemSet items, int amount, int depth) throws Unplannable {
            int missing = amount - stock.take(items, amount);
            if (missing > 0) {
                produce(items, missing, depth);
                stock.take(items, missing);
            }
        }

        /** Tools and stations are needed but not used up. */
        void ensure(ItemSet items, int depth) throws Unplannable {
            if (stock.count(items) == 0) {
                produce(items, 1, depth);
            }
        }

        void produce(ItemSet items, int amount, int depth) throws Unplannable {
            if (depth > MAX_DEPTH) {
                throw new Unplannable("the recipe chain for " + items.name() + " goes too deep");
            }
            if (!making.add(items)) {
                throw new Unplannable("making " + items.name() + " would need " + items.name() + " first");
            }
            try {
                List<Way> ways = book.ways(items, avoid);
                if (ways.isEmpty()) {
                    throw new Unplannable("I don't know a survival way to get " + items.name());
                }
                Unplannable firstProblem = null;
                for (Way way : ways) {
                    if (++tries > MAX_TRIES) {
                        throw new Unplannable("working out how to get " + items.name() + " took too long");
                    }
                    Saved saved = save();
                    try {
                        use(way, amount, depth + 1);
                        return;
                    } catch (Unplannable problem) {
                        restore(saved);
                        firstProblem = firstProblem == null ? problem : firstProblem;
                    }
                }
                throw firstProblem;
            } finally {
                making.remove(items);
            }
        }

        private Saved save() {
            Map<String, Integer> amounts = new LinkedHashMap<>();
            steps.forEach((key, pending) -> amounts.put(key, pending.amount));
            return new Saved(stock.copy(), amounts);
        }

        private void restore(Saved saved) {
            stock = saved.stock();
            steps.keySet().retainAll(saved.amounts().keySet());
            saved.amounts().forEach((key, amount) -> steps.get(key).amount = amount);
        }

        private void use(Way way, int amount, int next) throws Unplannable {
            switch (way) {
                case Mine mine -> {
                    if (mine.tools() != null) {
                        ensure(mine.tools(), next);
                    }
                    add("gather", mine.output(), amount, count -> new Step.Gather(mine.output(), mine.blocks(), count));
                    stock.add(mine.output(), amount);
                }
                case Hunt hunt -> {
                    add("hunt", hunt.output(), amount, count -> new Step.Hunt(hunt.output(), hunt.mobs(), count));
                    stock.add(hunt.output(), amount);
                }
                case Craft craft -> {
                    int times = Math.ceilDiv(amount, craft.yield());
                    if (craft.needsTable()) {
                        ensure(TABLE, next);
                    }
                    for (Ingredient ingredient : craft.ingredients()) {
                        need(ingredient.items(), ingredient.count() * times, next);
                    }
                    add("craft", craft.output(), times,
                        n -> new Step.Craft(craft.output(), craft.recipes(), n, craft.yield()));
                    stock.add(craft.output(), times * craft.yield());
                }
                case Smelt smelt -> {
                    ensure(FURNACE, next);
                    need(smelt.input(), amount, next);
                    fuel(amount, next);
                    add("smelt", smelt.output(), amount, n -> new Step.Smelt(smelt.input(), smelt.output(), n));
                    stock.add(smelt.output(), amount);
                }
            }
        }

        /** Coal already on hand goes first; otherwise planks, which every forest has. */
        void fuel(int smelts, int depth) throws Unplannable {
            int coal = Math.ceilDiv(smelts, SMELTS_PER_COAL);
            if (stock.count(COAL) >= coal) {
                stock.take(COAL, coal);
            } else {
                need(PLANKS, (int) Math.ceil(smelts / SMELTS_PER_PLANK), depth);
            }
        }

        private void add(String kind, ItemSet items, int amount, IntFunction<Step> make) {
            steps.computeIfAbsent(kind + items, key -> new Pending(make)).amount += amount;
        }

        List<Step> steps() {
            return steps.values().stream().map(Pending::build).toList();
        }
    }

    private static final class Pending {
        final IntFunction<Step> make;
        int amount;

        Pending(IntFunction<Step> make) {
            this.make = make;
        }

        Step build() {
            return make.apply(amount);
        }
    }
}
