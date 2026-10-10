package dev.minegoon;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bukkit.Material;

/**
 * Items that are interchangeable for a need, such as any planks for a crafting
 * table. A single item is a set of one.
 */
public record ItemSet(Set<Material> members) {

    public ItemSet {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("empty item set");
        }
        members = Set.copyOf(EnumSet.copyOf(members));
    }

    public static ItemSet of(Material material) {
        return new ItemSet(Set.of(material));
    }

    public static ItemSet of(Collection<Material> materials) {
        return new ItemSet(Set.copyOf(materials));
    }

    public boolean contains(Material material) {
        return members.contains(material);
    }

    public boolean containsAll(ItemSet other) {
        return members.containsAll(other.members);
    }

    public ItemSet union(ItemSet other) {
        EnumSet<Material> all = EnumSet.copyOf(members);
        all.addAll(other.members);
        return new ItemSet(all);
    }

    public boolean single() {
        return members.size() == 1;
    }

    /** Lowest-ordinal member, so the choice is stable. */
    public Material first() {
        return EnumSet.copyOf(members).iterator().next();
    }

    /**
     * "diamond", or "any log" after the most common last word, such as oak_log
     * and birch_log. Ties go to a word that is an item on its own, like cobblestone.
     */
    public String name() {
        if (single()) {
            return key(first());
        }
        Map<String, Long> lastWords = members.stream()
            .map(material -> material.name().substring(material.name().lastIndexOf('_') + 1))
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
        Comparator<String> preference = Comparator.<String>comparingLong(lastWords::get)
            .thenComparing(word -> members.stream().anyMatch(member -> member.name().equals(word)))
            .thenComparing(Comparator.reverseOrder());
        return "any " + Collections.max(lastWords.keySet(), preference).toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return members.stream().map(ItemSet::key).sorted().collect(Collectors.joining(",", "[", "]"));
    }

    public static String key(Material material) {
        return material.name().toLowerCase(Locale.ROOT);
    }

    /** Turns chat words such as "Diamonds" or "oak log" into an item, if one exists. */
    public static Material parse(String words) {
        String name = words.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("MINECRAFT:", "");
        for (String candidate : Arrays.asList(name, name.endsWith("S") ? name.substring(0, name.length() - 1) : name)) {
            Material material = Material.getMaterial(candidate);
            if (material != null && material.isItem() && !material.isAir() && !material.isLegacy()) {
                return material;
            }
        }
        return null;
    }
}
