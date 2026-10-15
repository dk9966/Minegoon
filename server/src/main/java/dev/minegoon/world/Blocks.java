package dev.minegoon.world;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;

/** How the helper's body relates to a block: can it stand in it, stand on it, or must it keep away. */
public final class Blocks {

    private static final Set<Material> HARMFUL = EnumSet.of(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.COBWEB, Material.SWEET_BERRY_BUSH,
        Material.POWDER_SNOW, Material.WITHER_ROSE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
        Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY, Material.POINTED_DRIPSTONE,
        Material.MAGMA_BLOCK, Material.CACTUS);

    private static final Set<Material> WATER = EnumSet.of(
        Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
        Material.SEAGRASS, Material.TALL_SEAGRASS);

    private Blocks() {
    }

    /**
     * The body can occupy this block: nothing to bump into, or something it climbs
     * inside or stands in, like a ladder, a snow layer, or a carpet. Small blocks
     * such as cocoa pods and lanterns are in the way even though they are not solid.
     */
    public static boolean passable(Material material) {
        if (material.isAir()) {
            return true;
        }
        if (HARMFUL.contains(material) || material == Material.BARRIER) {
            return false;
        }
        return !material.isCollidable() || Tag.CLIMBABLE.isTagged(material) || material == Material.SNOW
            || Tag.WOOL_CARPETS.isTagged(material) || material == Material.MOSS_CARPET
            || material == Material.PALE_MOSS_CARPET;
    }

    public static boolean water(Material material) {
        return WATER.contains(material);
    }

    /** Fluids that would pour into a hole if a neighbouring block were removed. */
    public static boolean fluid(Material material) {
        return material == Material.LAVA || water(material);
    }

    /** The body can stand on top of this block. */
    public static boolean ground(Material material) {
        if (HARMFUL.contains(material)) {
            return false;
        }
        return material.isOccluding()
            || Tag.LEAVES.isTagged(material)
            || Tag.IMPERMEABLE.isTagged(material)
            || Tag.ICE.isTagged(material)
            || material == Material.FARMLAND
            || material == Material.DIRT_PATH;
    }

    public static boolean harmful(Material material) {
        return HARMFUL.contains(material);
    }
}
