package dev.minegoon.planner;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BinaryOperator;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;

/**
 * Where raw materials come from when no recipe makes them. An item can have
 * more than one origin: wheat is a ripe crop in a village, or seeds grown
 * with bone meal. Everything else is crafted or smelted from these.
 * Ask for stone, not cobblestone: cobblestone blocks are someone's build or a
 * dungeon, and mining stone is how cobblestone is really gathered.
 */
public final class Sources {

    public sealed interface Source {
    }

    public record Mined(Set<Material> blocks) implements Source {
    }

    /** A crop block that only drops its item when fully grown. */
    public record Ripe(Material crop) implements Source {
    }

    /** Plant {@code seed} on tilled dirt and bone-meal it. The seed is a different item from the harvest. */
    public record Grown(Material seed, Material crop) implements Source {
    }

    /** Fill a container from a fluid already in the world. A bucket of water is not a crafting recipe. */
    public record Filled(Material container, Material fluid) implements Source {
    }

    public record Hunted(Set<EntityType> mobs) implements Source {
    }

    private static final Map<Material, List<Source>> SOURCES = new EnumMap<>(Material.class);

    static {
        mined(Material.COBBLESTONE, Material.STONE);
        mined(Material.COBBLED_DEEPSLATE, Material.DEEPSLATE);
        mined(Material.DIRT, Material.DIRT, Material.GRASS_BLOCK, Material.COARSE_DIRT, Material.ROOTED_DIRT);
        mined(Material.COAL, Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE);
        mined(Material.RAW_IRON, Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE);
        mined(Material.RAW_COPPER, Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE);
        mined(Material.RAW_GOLD, Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE);
        mined(Material.DIAMOND, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE);
        mined(Material.EMERALD, Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE);
        mined(Material.LAPIS_LAZULI, Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);
        mined(Material.REDSTONE, Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE);
        mined(Material.CLAY_BALL, Material.CLAY);
        mined(Material.FLINT, Material.GRAVEL);
        mined(Material.MELON_SLICE, Material.MELON);
        for (Material self : EnumSet.of(Material.SAND, Material.RED_SAND, Material.GRAVEL, Material.GRANITE,
                Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.CALCITE, Material.SANDSTONE,
                Material.RED_SANDSTONE, Material.OBSIDIAN, Material.PUMPKIN, Material.SUGAR_CANE, Material.CACTUS,
                Material.BAMBOO, Material.MOSS_BLOCK, Material.SNOW_BLOCK, Material.MUD, Material.DRIPSTONE_BLOCK)) {
            mined(self, self);
        }
        for (Material log : Tag.LOGS.getValues()) {
            if (!log.name().startsWith("STRIPPED_")) {
                mined(log, log);
            }
        }
        mined(Material.WHEAT_SEEDS, Material.SHORT_GRASS, Material.TALL_GRASS, Material.FERN, Material.LARGE_FERN);
        ripe(Material.WHEAT, Material.WHEAT);
        ripe(Material.CARROT, Material.CARROTS);
        ripe(Material.POTATO, Material.POTATOES);
        ripe(Material.BEETROOT, Material.BEETROOTS);
        grown(Material.WHEAT, Material.WHEAT_SEEDS, Material.WHEAT);
        filled(Material.WATER_BUCKET, Material.BUCKET, Material.WATER);
        filled(Material.LAVA_BUCKET, Material.BUCKET, Material.LAVA);
        hunted(Material.LEATHER, EntityType.COW);
        hunted(Material.BEEF, EntityType.COW);
        hunted(Material.PORKCHOP, EntityType.PIG);
        hunted(Material.MUTTON, EntityType.SHEEP);
        hunted(Material.WHITE_WOOL, EntityType.SHEEP);
        hunted(Material.CHICKEN, EntityType.CHICKEN);
        hunted(Material.FEATHER, EntityType.CHICKEN);
        hunted(Material.RABBIT_HIDE, EntityType.RABBIT);
        hunted(Material.BONE, EntityType.SKELETON);
        hunted(Material.ARROW, EntityType.SKELETON);
        hunted(Material.STRING, EntityType.SPIDER, EntityType.CAVE_SPIDER);
        hunted(Material.CARROT, EntityType.ZOMBIE);
        hunted(Material.POTATO, EntityType.ZOMBIE);
    }

    private Sources() {
    }

    public static List<Source> of(Material item) {
        return SOURCES.getOrDefault(item, List.of());
    }

    public static Set<Material> items() {
        return SOURCES.keySet();
    }

    private static void mined(Material item, Material... blocks) {
        add(item, new Mined(Set.of(blocks)), Mined.class, (a, b) -> {
            Set<Material> all = EnumSet.copyOf(a.blocks());
            all.addAll(b.blocks());
            return new Mined(all);
        });
    }

    private static void ripe(Material item, Material crop) {
        add(item, new Ripe(crop));
    }

    private static void grown(Material item, Material seed, Material crop) {
        add(item, new Grown(seed, crop));
    }

    private static void filled(Material item, Material container, Material fluid) {
        add(item, new Filled(container, fluid));
    }

    private static void hunted(Material item, EntityType... mobs) {
        add(item, new Hunted(EnumSet.copyOf(Set.of(mobs))), Hunted.class, (a, b) -> {
            Set<EntityType> all = EnumSet.copyOf(a.mobs());
            all.addAll(b.mobs());
            return new Hunted(all);
        });
    }

    private static void add(Material item, Source source) {
        SOURCES.computeIfAbsent(item, key -> new ArrayList<>()).add(source);
    }

    private static <S extends Source> void add(Material item, S source, Class<S> kind, BinaryOperator<S> merge) {
        List<Source> list = SOURCES.computeIfAbsent(item, key -> new ArrayList<>());
        for (int i = 0; i < list.size(); i++) {
            if (kind.isInstance(list.get(i))) {
                list.set(i, merge.apply(kind.cast(list.get(i)), source));
                return;
            }
        }
        list.add(source);
    }
}
