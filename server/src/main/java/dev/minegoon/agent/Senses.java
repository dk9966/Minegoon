package dev.minegoon.agent;

import dev.minegoon.ItemSet;
import dev.minegoon.MainThread;
import dev.minegoon.actions.Receipt;
import dev.minegoon.spawn.Helper;
import dev.minegoon.trips.Prospector;
import dev.minegoon.trips.Sleeper;
import dev.minegoon.world.Pos;
import dev.minegoon.world.Structures;
import dev.minegoon.world.Vision;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** What the helper can tell about itself and what is around it, in words for a model. */
public final class Senses {

    private static final int CREATURES = 48;
    private static final int BLOCKS = 16;
    private static final int BIOMES = 8;

    private final MainThread main;
    private final Helper helper;
    private final Vision vision;
    private final Structures structures;
    private final Sleeper sleeper;

    public Senses(MainThread main, Helper helper, Vision vision, Structures structures, Sleeper sleeper) {
        this.main = main;
        this.helper = helper;
        this.vision = vision;
        this.structures = structures;
        this.sleeper = sleeper;
    }

    /** Where the helper is and how it is doing, in one line. */
    public String state() {
        if (!main.call(helper::online)) {
            return "not in the world";
        }
        String biome = vision.biomeHere();
        boolean night = sleeper.night();
        return main.call(() -> {
            Player player = helper.player();
            Location at = player.getLocation();
            return "at %d %d %d in %s, health %.0f/20, food %d/20, %s".formatted(at.getBlockX(), at.getBlockY(),
                at.getBlockZ(), biome, player.getHealth(), player.getFoodLevel(), night ? "night" : "day");
        });
    }

    /** Everything the helper holds, counted by item. */
    public String inventory() {
        Map<Material, Integer> counts = main.call(() -> {
            Map<Material, Integer> held = new EnumMap<>(Material.class);
            if (helper.online()) {
                for (ItemStack stack : helper.player().getInventory().getContents()) {
                    if (stack != null && !stack.getType().isAir()) {
                        held.merge(stack.getType(), stack.getAmount(), Integer::sum);
                    }
                }
            }
            return held;
        });
        if (counts.isEmpty()) {
            return "Holding nothing.";
        }
        return "Holding " + counts.entrySet().stream()
            .map(entry -> entry.getValue() + " " + ItemSet.key(entry.getKey()))
            .collect(Collectors.joining(", ")) + ".";
    }

    /** Biomes, structures, and creatures in sight, and where the given blocks are, if any are. */
    public String look(Set<Material> blocks) {
        if (!main.call(helper::online)) {
            return "Not in the world.";
        }
        Pos feet = main.call(() -> Pos.of(helper.player().getLocation()));
        List<String> parts = new ArrayList<>();
        parts.add("Biomes in sight: " + none(vision.biomes().stream().limit(BIOMES)
            .map(patch -> patch.biome() + " " + away(feet, patch.at())).toList()) + ".");
        parts.add("Structures: " + none(structures.inSight(Set.of()).stream()
            .map(sighting -> sighting.name() + " " + away(feet, sighting.at())).toList()) + ".");
        parts.add("Creatures: " + none(creatures()) + ".");
        for (Material block : blocks) {
            List<Pos> seen = vision.blocks(EnumSet.of(block), BLOCKS, candidate -> true);
            parts.add(ItemSet.key(block) + ": " + (seen.isEmpty() ? "none in sight"
                : seen.size() + (seen.size() == BLOCKS ? " or more" : "") + " in sight, nearest at " + seen.getFirst())
                + ".");
        }
        return String.join(" ", parts);
    }

    /** An online player in the helper's world, by name. */
    public Optional<UUID> player(String name) {
        return main.call(() -> {
            Player player = Bukkit.getPlayerExact(name);
            return player != null && helper.online() && player.getWorld().equals(helper.player().getWorld())
                ? Optional.of(player.getUniqueId()) : Optional.empty();
        });
    }

    public <C> Tool<C> lookAroundTool() {
        return Tool.of("look_around", """
            Says what is in sight: biomes, structures, and creatures, and where any of the given blocks are. \
            Looking does not move the helper.""", """
            {"type": "object", "properties": {
              "blocks": {"type": "array", "items": {"type": "string"},
                         "description": "Block ids to look for, such as diamond_ore. May be empty."}
            }}""", (args, context) -> {
                Set<Material> blocks = EnumSet.noneOf(Material.class);
                for (String name : args.texts("blocks")) {
                    Material block = Material.matchMaterial(name);
                    if (block == null || !block.isBlock()) {
                        throw new Args.Refused("there is no block called " + name);
                    }
                    blocks.add(block);
                }
                return Receipt.confirmed("look_around", look(blocks));
            });
    }

    public <C> Tool<C> inventoryTool() {
        return Tool.of("inventory", "Says everything the helper is holding.",
            (args, context) -> Receipt.confirmed("inventory", inventory()));
    }

    private List<String> creatures() {
        Set<EntityType> kinds = EnumSet.allOf(EntityType.class);
        List<UUID> seen = vision.creatures(kinds, entity -> !(entity instanceof Player), CREATURES);
        Map<String, Long> counts = main.call(() -> seen.stream()
            .map(Bukkit::getEntity)
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(entity -> Prospector.key(entity.getType()), TreeMap::new,
                Collectors.counting())));
        return counts.entrySet().stream().map(entry -> entry.getValue() + " " + entry.getKey()).toList();
    }

    private static String away(Pos from, Pos to) {
        long blocks = Math.round(Math.hypot(to.x() - from.x(), to.z() - from.z()));
        return blocks < 8 ? "(here)" : "(" + blocks + " blocks " + direction(to.x() - from.x(), to.z() - from.z()) + ")";
    }

    /** North is negative z in Minecraft. */
    private static String direction(int dx, int dz) {
        String ns = Math.abs(dz) * 2 < Math.abs(dx) ? "" : dz < 0 ? "north" : "south";
        String ew = Math.abs(dx) * 2 < Math.abs(dz) ? "" : dx < 0 ? "west" : "east";
        return ns + ew;
    }

    private static String none(List<String> names) {
        return names.isEmpty() ? "none" : String.join(", ", names);
    }
}
