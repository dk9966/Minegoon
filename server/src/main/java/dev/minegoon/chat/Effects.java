package dev.minegoon.chat;

import dev.minegoon.MainThread;
import dev.minegoon.spawn.Helper;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Says which potion effects are on the helper. Harmful ones are named first,
 * since poison and wither are the ones that change what to do next.
 */
public final class Effects {

    private static final String[] ROMAN = {
        "", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"
    };

    private final MainThread main;
    private final Helper helper;

    public Effects(MainThread main, Helper helper) {
        this.main = main;
        this.helper = helper;
    }

    /** One line for chat. The player is read on the server thread. */
    public String report() {
        return main.call(() -> {
            if (!helper.online() || helper.player().isDead()) {
                return "I'm not standing in the world right now.";
            }
            Player player = helper.player();
            List<Active> active = new ArrayList<>();
            for (PotionEffect effect : player.getActivePotionEffects()) {
                active.add(active(effect));
            }
            return describe(active);
        });
    }

    static String describe(List<Active> effects) {
        if (effects.isEmpty()) {
            return "Nothing is affecting me.";
        }
        List<String> parts = effects.stream()
            .sorted(Comparator.comparing(Active::kind).thenComparingInt(Effects::length).thenComparing(Active::name))
            .map(Effects::fragment)
            .toList();
        return sentence(join(parts));
    }

    private static Active active(PotionEffect effect) {
        PotionEffectType type = effect.getType();
        boolean infinite = effect.isInfinite() || effect.getDuration() < 0;
        return new Active(type.getKey().getKey().replace('_', ' '), effect.getAmplifier(), effect.getDuration(),
            infinite, kind(type.getEffectCategory()));
    }

    private static Kind kind(PotionEffectType.Category category) {
        return switch (category) {
            case HARMFUL -> Kind.HARMFUL;
            case NEUTRAL -> Kind.NEUTRAL;
            case BENEFICIAL -> Kind.BENEFICIAL;
        };
    }

    private static String fragment(Active effect) {
        String labeled = effect.name() + level(effect.amplifier());
        if (effect.infinite()) {
            return labeled + ", not wearing off";
        }
        return labeled + " for " + time(effect.durationTicks());
    }

    /** Level I is left off, the way the game shows it. Past X, the number is clearer than a long numeral. */
    private static String level(int amplifier) {
        int level = amplifier + 1;
        if (level <= 1) {
            return "";
        }
        return " " + (level < ROMAN.length ? ROMAN[level] : Integer.toString(level));
    }

    private static String time(int ticks) {
        int seconds = Math.max(ticks, 0) / 20;
        if (seconds == 0) {
            return "a moment";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remain = seconds % 60;
        List<String> parts = new ArrayList<>(3);
        if (hours > 0) {
            parts.add(count(hours, "hour"));
        }
        if (minutes > 0) {
            parts.add(count(minutes, "minute"));
        }
        if (remain > 0) {
            parts.add(count(remain, "second"));
        }
        return String.join(" ", parts);
    }

    private static String count(int amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }

    /** An effect that does not end sorts after every timed one. */
    private static int length(Active effect) {
        return effect.infinite() ? Integer.MAX_VALUE : Math.max(effect.durationTicks(), 0);
    }

    private static String join(List<String> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        if (parts.size() == 2) {
            return parts.get(0) + " and " + parts.get(1);
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + ", and " + parts.getLast();
    }

    private static String sentence(String body) {
        return Character.toUpperCase(body.charAt(0)) + body.substring(1) + ".";
    }

    enum Kind {
        HARMFUL, NEUTRAL, BENEFICIAL
    }

    record Active(String name, int amplifier, int durationTicks, boolean infinite, Kind kind) {
    }
}
