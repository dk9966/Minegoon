package dev.minegoon.planner;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.EntityType;

/**
 * Where raw materials come from in untouched terrain: blocks to mine and
 * passive animals to hunt. Everything else is crafted or smelted from these.
 * Ask for stone, not cobblestone: cobblestone blocks are someone's build or a
 * dungeon, and mining stone is how cobblestone is really gathered.
 */
public final class Sources {

    public sealed interface Source {
    }

    public record Mined(Set<Material> blocks) implements Source {
    }

    public record Hunted(Set<EntityType> mobs) implements Source {
    }

    private static final Map<Material, Source> SOURCES = new EnumMap<>(Material.class);

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
        hunted(Material.LEATHER, EntityType.COW);
        hunted(Material.BEEF, EntityType.COW);
        hunted(Material.PORKCHOP, EntityType.PIG);
        hunted(Material.MUTTON, EntityType.SHEEP);
        hunted(Material.WHITE_WOOL, EntityType.SHEEP);
        hunted(Material.CHICKEN, EntityType.CHICKEN);
        hunted(Material.FEATHER, EntityType.CHICKEN);
        hunted(Material.RABBIT_HIDE, EntityType.RABBIT);
    }

    private Sources() {
    }

    public static Optional<Source> of(Material item) {
        return Optional.ofNullable(SOURCES.get(item));
    }

    public static Set<Material> items() {
        return SOURCES.keySet();
    }

    private static void mined(Material item, Material... blocks) {
        SOURCES.merge(item, new Mined(Set.of(blocks)), (a, b) -> {
            Set<Material> all = EnumSet.copyOf(((Mined) a).blocks());
            all.addAll(((Mined) b).blocks());
            return new Mined(all);
        });
    }

    private static void hunted(Material item, EntityType mob) {
        SOURCES.put(item, new Hunted(Set.of(mob)));
    }
}
