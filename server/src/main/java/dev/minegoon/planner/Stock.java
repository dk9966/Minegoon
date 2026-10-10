package dev.minegoon.planner;

import dev.minegoon.ItemSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;

/**
 * What the helper will be holding as the plan unfolds: the real inventory, plus
 * items earlier steps will produce. Produced items are known only as a set
 * ("some log"), so they can satisfy any need that the whole set satisfies.
 */
final class Stock {

    private final Map<Material, Integer> real = new EnumMap<>(Material.class);
    private final List<Produced> produced = new ArrayList<>();

    private static final class Produced {
        final ItemSet items;
        int amount;

        Produced(ItemSet items, int amount) {
            this.items = items;
            this.amount = amount;
        }
    }

    Stock(Map<Material, Integer> inventory) {
        real.putAll(inventory);
    }

    Stock copy() {
        Stock copy = new Stock(real);
        produced.forEach(entry -> copy.add(entry.items, entry.amount));
        return copy;
    }

    void add(ItemSet items, int amount) {
        produced.add(new Produced(items, amount));
    }

    int count(ItemSet wanted) {
        int total = 0;
        for (Map.Entry<Material, Integer> entry : real.entrySet()) {
            if (wanted.contains(entry.getKey())) {
                total += entry.getValue();
            }
        }
        for (Produced entry : produced) {
            if (wanted.containsAll(entry.items)) {
                total += entry.amount;
            }
        }
        return total;
    }

    /** Takes up to {@code amount} and returns how many were taken. */
    int take(ItemSet wanted, int amount) {
        int left = amount;
        for (Map.Entry<Material, Integer> entry : real.entrySet()) {
            if (left > 0 && wanted.contains(entry.getKey())) {
                int taken = Math.min(left, entry.getValue());
                entry.setValue(entry.getValue() - taken);
                left -= taken;
            }
        }
        for (Produced entry : produced) {
            if (left > 0 && wanted.containsAll(entry.items)) {
                int taken = Math.min(left, entry.amount);
                entry.amount -= taken;
                left -= taken;
            }
        }
        return amount - left;
    }
}
