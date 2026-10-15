package dev.minegoon.jobs;

import dev.minegoon.ItemSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/** One job's work. Stored as JSON, keyed by the record's simple name. */
public sealed interface Step {

    String describe();

    /** The item whose count in the inventory shows the step's progress, or null. */
    ItemSet counted();

    /**
     * Mine blocks until {@code count} more of the item are in the inventory.
     * Ripe crops are broken only when fully grown.
     */
    record Gather(ItemSet item, Set<Material> blocks, int count, boolean ripe) implements Step {
        public String describe() {
            return (ripe ? "harvest " : "gather ") + count + " " + item.name();
        }

        public ItemSet counted() {
            return item;
        }
    }

    /** Hunt passive animals for their drops. */
    record Hunt(ItemSet item, Set<EntityType> mobs, int count) implements Step {
        public String describe() {
            return "hunt for " + count + " " + item.name();
        }

        public ItemSet counted() {
            return item;
        }
    }

    /** Hoe, plant, bone-meal, and harvest. */
    record Grow(ItemSet crop, Material seed, Material block, int count) implements Step {
        public String describe() {
            return "grow " + count + " " + crop.name();
        }

        public ItemSet counted() {
            return crop;
        }
    }

    /** Fill containers from a fluid in the world. */
    record Fill(ItemSet item, Material container, Material fluid, int count) implements Step {
        public String describe() {
            return "fill " + count + " " + item.name();
        }

        public ItemSet counted() {
            return item;
        }
    }

    /** Hunt a passive animal and eat it. Done when the helper is no longer hungry. */
    record Meal() implements Step {
        public String describe() {
            return "hunt for a meal";
        }

        public ItemSet counted() {
            return null;
        }
    }

    /** Place a bed and sleep through the night. */
    record Sleep() implements Step {
        public String describe() {
            return "sleep until morning";
        }

        public ItemSet counted() {
            return null;
        }
    }

    /** Dig in and wait out the night, for a helper with nothing to fight with. */
    record Shelter() implements Step {
        public String describe() {
            return "dig in until morning";
        }

        public ItemSet counted() {
            return null;
        }
    }

    /** Craft {@code times} times with the first recipe the inventory can fill. */
    record Craft(ItemSet output, List<String> recipes, int times, int yield) implements Step {
        public String describe() {
            return "craft " + times * yield + " " + output.name();
        }

        public ItemSet counted() {
            return output;
        }
    }

    record Smelt(ItemSet input, ItemSet output, int count) implements Step {
        public String describe() {
            return "smelt " + count + " " + output.name();
        }

        public ItemSet counted() {
            return output;
        }
    }

    /** Walk back to the owner, or to where the task was given, and hand items over. */
    record Deliver(ItemSet item, int count) implements Step {
        public String describe() {
            return "bring back " + count + " " + item.name();
        }

        public ItemSet counted() {
            return item;
        }
    }

    /** Go back for dropped items after a death, before they despawn. */
    record Recover(String world, int x, int y, int z, long until) implements Step {
        public String describe() {
            return "recover dropped items at " + x + " " + y + " " + z;
        }

        public ItemSet counted() {
            return null;
        }
    }

    Map<String, Class<? extends Step>> KINDS = Arrays.stream(Step.class.getPermittedSubclasses())
        .map(type -> type.asSubclass(Step.class))
        .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));
}
