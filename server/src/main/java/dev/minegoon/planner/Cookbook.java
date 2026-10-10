package dev.minegoon.planner;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.CraftingGrid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Keyed;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.FurnaceRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;

/**
 * Every survival way to obtain each item, read from the running server's own
 * recipes, with a rough cost per item so the planner can pick the cheapest way.
 * Costs settle by repeated relaxation, which also keeps silly loops out, such as
 * making iron ingots from an iron block.
 */
public final class Cookbook {

    public sealed interface Way {
        ItemSet output();

        /** Identifies this way for one output item, so a failing way can be avoided on replanning. */
        default String id(Material item) {
            return getClass().getSimpleName().toLowerCase(Locale.ROOT) + ":" + ItemSet.key(item);
        }
    }

    public record Mine(ItemSet output, Set<Material> blocks, ItemSet tools) implements Way {
    }

    public record Hunt(ItemSet output, Set<EntityType> mobs) implements Way {
    }

    public record Craft(ItemSet output, List<Ingredient> ingredients, int yield, boolean needsTable,
                        List<String> recipes) implements Way {
    }

    public record Smelt(ItemSet output, ItemSet input) implements Way {
    }

    public record Ingredient(ItemSet items, int count) {
    }

    private static final double MINE = 2;
    private static final double HUNT = 8;
    private static final double TOOL_SHARE = 0.2;
    private static final double CRAFT = 0.5;
    private static final double SMELT = 2;
    private static final int RELAXATIONS = 64;
    private static final Object NO_SHAPE = new Object();

    private final Map<Material, List<Way>> ways = new EnumMap<>(Material.class);
    private final Map<Material, Double> costs = new EnumMap<>(Material.class);
    private final List<Material> tools;

    private Cookbook(List<Material> tools) {
        this.tools = tools;
    }

    /** Main thread: reads the server's recipe list. */
    public static Cookbook load() {
        List<Material> tools = new ArrayList<>();
        for (Tag<Material> tag : List.of(Tag.ITEMS_PICKAXES, Tag.ITEMS_SHOVELS, Tag.ITEMS_AXES, Tag.ITEMS_HOES)) {
            tools.addAll(tag.getValues());
        }
        tools.add(Material.SHEARS);
        Cookbook book = new Cookbook(tools);
        book.addSources();
        for (Iterator<Recipe> it = Bukkit.recipeIterator(); it.hasNext(); ) {
            book.addRecipe(it.next());
        }
        book.settleCosts();
        return book;
    }

    public double cost(ItemSet items) {
        return items.members().stream().mapToDouble(this::cost).min().orElse(Double.POSITIVE_INFINITY);
    }

    public double cost(Material item) {
        return costs.getOrDefault(item, Double.POSITIVE_INFINITY);
    }

    /**
     * Every way to get any of {@code items}, best first. When several members are
     * made the same way, such as planks from their own logs, the first answer
     * covers them all ("any planks from any log"), so the world decides which
     * wood is used.
     */
    public List<Way> ways(ItemSet items, Set<String> avoid) {
        Map<Material, List<Way>> usable = new LinkedHashMap<>();
        for (Material member : items.members()) {
            List<Way> found = ways.getOrDefault(member, List.of()).stream()
                .filter(way -> !avoid.contains(way.id(member)))
                .filter(way -> wayCost(way) < Double.POSITIVE_INFINITY)
                .sorted(Comparator.comparingDouble(this::wayCost))
                .toList();
            if (!found.isEmpty()) {
                usable.put(member, found);
            }
        }
        List<Way> ranked = new ArrayList<>();
        if (usable.size() > 1) {
            Way merged = mergeAlike(usable.values().stream().map(List::getFirst).toList());
            if (merged != null) {
                ranked.add(merged);
            }
        }
        usable.values().stream().flatMap(List::stream).distinct()
            .sorted(Comparator.comparingDouble(this::wayCost))
            .forEach(ranked::add);
        return ranked;
    }

    /** Merges the largest group of members that are all obtained the same way, or returns null. */
    private Way mergeAlike(List<Way> cheapest) {
        Map<Object, List<Way>> groups = new LinkedHashMap<>();
        for (Way way : cheapest) {
            groups.computeIfAbsent(shape(way), key -> new ArrayList<>()).add(way);
        }
        List<Way> alike = groups.entrySet().stream()
            .filter(entry -> entry.getKey() != NO_SHAPE)
            .map(Map.Entry::getValue)
            .max(Comparator.comparingInt(List::size))
            .orElse(List.of());
        if (alike.size() < 2) {
            return null;
        }
        ItemSet output = alike.stream().map(Way::output).reduce(ItemSet::union).orElseThrow();
        return switch (alike.getFirst()) {
            case Mine first -> {
                Set<Material> blocks = EnumSet.noneOf(Material.class);
                alike.forEach(way -> blocks.addAll(((Mine) way).blocks()));
                yield new Mine(output, blocks, toolsFor(blocks));
            }
            case Hunt first -> {
                Set<EntityType> mobs = EnumSet.noneOf(EntityType.class);
                alike.forEach(way -> mobs.addAll(((Hunt) way).mobs()));
                yield new Hunt(output, mobs);
            }
            case Craft first -> {
                ItemSet anyInput = alike.stream().map(way -> ((Craft) way).ingredients().getFirst().items())
                    .reduce(ItemSet::union).orElseThrow();
                List<String> recipes = alike.stream().flatMap(way -> ((Craft) way).recipes().stream()).toList();
                yield new Craft(output, List.of(new Ingredient(anyInput, first.ingredients().getFirst().count())),
                    first.yield(), first.needsTable(), recipes);
            }
            case Smelt first -> null;
        };
    }

    /** Ways with equal shapes can be done as one step for any of their outputs. */
    private static Object shape(Way way) {
        return switch (way) {
            case Mine mine -> Mine.class;
            case Hunt hunt -> Hunt.class;
            case Craft craft when craft.ingredients().size() == 1 ->
                List.of(craft.yield(), craft.needsTable(), craft.ingredients().getFirst().count());
            default -> NO_SHAPE;
        };
    }

    /** Tools that let every one of these blocks drop its item, or null if a bare hand is enough. */
    public ItemSet toolsFor(Set<Material> blocks) {
        Set<Material> acceptable = null;
        for (Material block : blocks) {
            BlockData data = block.createBlockData();
            if (!data.requiresCorrectToolForDrops()) {
                continue;
            }
            Set<Material> fits = tools.stream()
                .filter(tool -> data.isPreferredTool(new ItemStack(tool)))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
            if (acceptable == null) {
                acceptable = fits;
            } else {
                acceptable.retainAll(fits);
            }
        }
        return acceptable == null || acceptable.isEmpty() ? null : ItemSet.of(acceptable);
    }

    private void addSources() {
        for (Material item : Sources.items()) {
            switch (Sources.of(item).orElseThrow()) {
                case Sources.Mined mined -> add(item, new Mine(ItemSet.of(item), mined.blocks(), toolsFor(mined.blocks())));
                case Sources.Hunted hunted -> add(item, new Hunt(ItemSet.of(item), hunted.mobs()));
            }
        }
    }

    private void addRecipe(Recipe recipe) {
        if (!(recipe instanceof Keyed keyed)) {
            return;
        }
        Material result = recipe.getResult().getType();
        if (result.isAir()) {
            return;
        }
        if (recipe instanceof FurnaceRecipe furnace) {
            ItemSet input = items(furnace.getInputChoice());
            if (input != null) {
                add(result, new Smelt(ItemSet.of(result), input));
            }
            return;
        }
        List<RecipeChoice> cells = CraftingGrid.cells(recipe);
        if (cells.isEmpty()) {
            return;
        }
        Map<ItemSet, Integer> counts = new LinkedHashMap<>();
        for (RecipeChoice cell : cells) {
            ItemSet items = items(cell);
            if (items == null) {
                return;
            }
            counts.merge(items, 1, Integer::sum);
        }
        List<Ingredient> ingredients = counts.entrySet().stream()
            .map(entry -> new Ingredient(entry.getKey(), entry.getValue())).toList();
        add(result, new Craft(ItemSet.of(result), ingredients, recipe.getResult().getAmount(),
            CraftingGrid.needsTable(recipe), List.of(keyed.getKey().toString())));
    }

    private static ItemSet items(RecipeChoice choice) {
        Set<Material> materials = new HashSet<>();
        if (choice instanceof RecipeChoice.MaterialChoice material) {
            materials.addAll(material.getChoices());
        } else if (choice instanceof RecipeChoice.ExactChoice exact) {
            exact.getChoices().forEach(stack -> materials.add(stack.getType()));
        }
        materials.removeIf(Material::isAir);
        return materials.isEmpty() ? null : ItemSet.of(materials);
    }

    private void add(Material item, Way way) {
        ways.computeIfAbsent(item, key -> new ArrayList<>()).add(way);
    }

    private void settleCosts() {
        for (int round = 0; round < RELAXATIONS; round++) {
            boolean changed = false;
            for (Map.Entry<Material, List<Way>> entry : ways.entrySet()) {
                double best = entry.getValue().stream().mapToDouble(this::wayCost).min().orElse(Double.POSITIVE_INFINITY);
                if (best < cost(entry.getKey()) - 1e-9) {
                    costs.put(entry.getKey(), best);
                    changed = true;
                }
            }
            if (!changed) {
                return;
            }
        }
    }

    private double wayCost(Way way) {
        return switch (way) {
            case Mine mine -> MINE + (mine.tools() == null ? 0 : TOOL_SHARE * cost(mine.tools()));
            case Hunt hunt -> HUNT;
            case Craft craft -> craft.ingredients().stream()
                .mapToDouble(ingredient -> ingredient.count() * cost(ingredient.items())).sum() / craft.yield() + CRAFT;
            case Smelt smelt -> cost(smelt.input()) + SMELT;
        };
    }

    /** For the startup log: how many items have at least one survival way. */
    public int knownItems() {
        return (int) costs.values().stream().filter(cost -> cost < Double.POSITIVE_INFINITY).count();
    }
}
