package dev.minegoon.safety;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;

/**
 * Block types that rarely occur in untouched terrain. Vanilla does not record who
 * placed a block, so these are treated as someone's build. Villages and other
 * structures contain some of them too; leaving those alone is the safe side.
 */
final class PlayerMade {

    private static final List<Tag<Material>> TAGS = List.of(
        Tag.PLANKS, Tag.WOOL, Tag.WOOL_CARPETS, Tag.BEDS, Tag.DOORS, Tag.TRAPDOORS, Tag.FENCES,
        Tag.FENCE_GATES, Tag.ALL_SIGNS, Tag.BANNERS, Tag.CANDLES, Tag.SHULKER_BOXES, Tag.ANVIL,
        Tag.FLOWER_POTS, Tag.BUTTONS, Tag.PRESSURE_PLATES, Tag.RAILS, Tag.STAIRS, Tag.SLABS, Tag.WALLS,
        Tag.CAULDRONS);

    private static final List<String> NAME_PARTS = List.of(
        "GLASS", "CONCRETE", "GLAZED_TERRACOTTA", "BRICK", "POLISHED_", "CUT_", "SMOOTH_", "CHISELED_",
        "STRIPPED_", "PURPUR", "QUARTZ_", "COPPER_BULB", "COPPER_GRATE", "LANTERN", "TORCH");

    private static final Set<Material> EXTRA = EnumSet.of(
        Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.CHEST, Material.TRAPPED_CHEST,
        Material.BARREL, Material.ENDER_CHEST, Material.CRAFTING_TABLE, Material.FURNACE,
        Material.BLAST_FURNACE, Material.SMOKER, Material.BOOKSHELF, Material.CHISELED_BOOKSHELF,
        Material.LADDER, Material.SEA_LANTERN, Material.HAY_BLOCK, Material.REDSTONE_LAMP,
        Material.REDSTONE_WIRE, Material.REPEATER, Material.COMPARATOR, Material.PISTON,
        Material.STICKY_PISTON, Material.OBSERVER, Material.HOPPER, Material.DISPENSER, Material.DROPPER,
        Material.LEVER, Material.TRIPWIRE_HOOK, Material.NOTE_BLOCK, Material.JUKEBOX,
        Material.ENCHANTING_TABLE, Material.BREWING_STAND, Material.LECTERN, Material.LOOM,
        Material.CARTOGRAPHY_TABLE, Material.FLETCHING_TABLE, Material.SMITHING_TABLE,
        Material.STONECUTTER, Material.GRINDSTONE, Material.BELL, Material.IRON_BARS,
        Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK, Material.EMERALD_BLOCK,
        Material.LAPIS_BLOCK, Material.REDSTONE_BLOCK, Material.COAL_BLOCK, Material.BEACON,
        Material.TARGET, Material.END_ROD, Material.SCAFFOLDING, Material.BEEHIVE, Material.COMPOSTER,
        Material.TNT, Material.WHITE_TERRACOTTA, Material.ARMOR_STAND, Material.ITEM_FRAME,
        Material.PAINTING, Material.CAMPFIRE, Material.SOUL_CAMPFIRE);

    private static final Set<Material> ALL = build();

    private PlayerMade() {
    }

    static boolean is(Material material) {
        return ALL.contains(material);
    }

    private static Set<Material> build() {
        Set<Material> all = EnumSet.copyOf(EXTRA);
        TAGS.forEach(tag -> all.addAll(tag.getValues()));
        for (Material material : Material.values()) {
            if (material.isBlock() && !material.isLegacy()
                && NAME_PARTS.stream().anyMatch(part -> material.name().contains(part))) {
                all.add(material);
            }
        }
        return all;
    }
}
