package dev.minegoon.knowledge;

import dev.minegoon.ItemSet;
import dev.minegoon.jobs.Step;
import dev.minegoon.knowledge.Knowledge.Offer;
import dev.minegoon.knowledge.Rulings.Ruling;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

/**
 * Whether to leave the current step for something in sight.
 *
 * <p>The trigger is a mismatch, not a timer and not "something is nearby". An
 * offer matches when it is in sight, it covers a need the task still has, and
 * the step already running is not that offer. A script is taken. An ask is put
 * to the model only when the body could win, and a take that paid off is
 * remembered, so the next time the same offer is a script. A village walked
 * past with a full pack matches nothing and costs nothing.
 */
public final class Notices {

    /** Crop item to the block that drops it when ripe. */
    private static final Map<String, Material> CROPS = Map.of(
        "wheat", Material.WHEAT,
        "carrot", Material.CARROTS,
        "potato", Material.POTATOES,
        "beetroot", Material.BEETROOTS);

    /** What a look around found. A mob maps to whether the body would win the fight. */
    public record Sight(Set<String> structures, Map<String, Boolean> mobs) {
    }

    /** What to do about one offer. {@code remembered} was learned on an earlier run. */
    public sealed interface Spot {
        String offer();

        Step step();

        record Act(String offer, Step step, String say, boolean remembered) implements Spot {
        }

        record Ask(String offer, Step step, String card) implements Spot {
        }
    }

    private Notices() {
    }

    /**
     * The first offer worth acting on, or the first one worth asking about when
     * no script matches. Empty when nothing in sight changes the task.
     */
    public static Optional<Spot> spot(List<Offer> offers, Set<String> needs, Sight sight, Set<String> busy,
                                      Set<String> settled, Function<String, Optional<Ruling>> rulings,
                                      String situation) {
        Spot ask = null;
        for (Offer offer : offers) {
            if (settled.contains(offer.name()) || !offer.matches(needs) || overlaps(offer, busy) || !seen(offer, sight)) {
                continue;
            }
            Step step = step(offer);
            if (step == null) {
                continue;
            }
            Optional<Ruling> ruling = rulings.apply(offer.name());
            if (offer.ask()) {
                boolean winnable = Boolean.TRUE.equals(sight.mobs().get(offer.name()));
                if (ruling.filter(Ruling::kept).isPresent()) {
                    if (winnable) {
                        return Optional.of(new Spot.Act(offer.name(), step, remembered(offer), true));
                    }
                    continue;
                }
                if (winnable && ask == null) {
                    ask = new Spot.Ask(offer.name(), step, card(offer, needs, situation));
                }
                continue;
            }
            return Optional.of(new Spot.Act(offer.name(), step, script(offer), false));
        }
        return Optional.ofNullable(ask);
    }

    /** Item and mob names the step is already collecting, so the same offer is not taken twice. */
    public static Set<String> busy(Step step) {
        return switch (step) {
            case Step.Gather gather -> Set.copyOf(keys(gather.item()));
            case Step.Hunt hunt -> {
                Set<String> names = keys(hunt.item());
                hunt.mobs().forEach(mob -> names.add(mob.getKey().value()));
                yield Set.copyOf(names);
            }
            default -> Set.of();
        };
    }

    private static boolean seen(Offer offer, Sight sight) {
        return switch (offer.see()) {
            case "structure" -> sight.structures().contains(offer.name());
            case "mob" -> sight.mobs().containsKey(offer.name());
            default -> false;
        };
    }

    private static boolean overlaps(Offer offer, Set<String> busy) {
        if (busy.contains(offer.name())) {
            return true;
        }
        for (String give : offer.gives()) {
            if (busy.contains(give)) {
                return true;
            }
        }
        return false;
    }

    private static Step step(Offer offer) {
        if (offer.take().equals("harvest")) {
            Set<Material> items = EnumSet.noneOf(Material.class);
            Set<Material> blocks = EnumSet.noneOf(Material.class);
            for (String give : offer.gives()) {
                Material item = Material.matchMaterial(give);
                Material block = CROPS.getOrDefault(give, item);
                if (item != null && item != Material.AIR) {
                    items.add(item);
                }
                if (block != null && block != Material.AIR) {
                    blocks.add(block);
                }
            }
            if (items.isEmpty() || blocks.isEmpty()) {
                return null;
            }
            return new Step.Gather(ItemSet.of(items), blocks, offer.count(), true);
        }
        Material drop = offer.gives().stream().map(Material::matchMaterial)
            .filter(item -> item != null && item != Material.AIR).findFirst().orElse(null);
        Optional<EntityType> mob = mob(offer.name());
        if (!offer.take().equals("hunt") || drop == null || mob.isEmpty()) {
            return null;
        }
        return new Step.Hunt(ItemSet.of(drop), EnumSet.of(mob.get()), offer.count());
    }

    private static Optional<EntityType> mob(String name) {
        try {
            return Optional.of(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    private static Set<String> keys(ItemSet items) {
        Set<String> names = new LinkedHashSet<>();
        items.members().forEach(item -> names.add(ItemSet.key(item)));
        return names;
    }

    private static String script(Offer offer) {
        String what = offer.take().equals("harvest") ? "harvest what it has" : "hunt it";
        return "There's " + article(offer.name()) + " in sight, so I'll " + what + ".";
    }

    private static String remembered(Offer offer) {
        return "There's " + article(offer.name()) + " in sight, and taking it has worked before.";
    }

    private static String article(String name) {
        return "aeiou".indexOf(name.charAt(0)) >= 0 ? "an " + name.replace('_', ' ') : "a " + name.replace('_', ' ');
    }

    private static String card(Offer offer, Set<String> needs, String situation) {
        String danger = offer.note().isBlank() ? "Nothing unusual about the danger." : offer.note();
        String kit = situation == null || situation.isBlank() ? "No reading on health or kit." : situation;
        return """
            Need: %s
            Detour: %s the %s for %s.
            %s
            %s
            Say whether to do it now. No is normal when it does not help the goal.
            """.formatted(String.join(", ", needs), offer.take(), offer.name().replace('_', ' '),
            String.join(", ", offer.gives()), danger, kit);
    }
}
