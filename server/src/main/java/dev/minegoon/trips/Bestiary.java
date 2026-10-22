package dev.minegoon.trips;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Enderman;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.MagmaCube;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PigZombie;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Spider;

/**
 * What the helper knows about every mob before it meets one: how much health
 * it has, how hard and how often it hits on normal difficulty, and how to deal
 * with it. Numbers are from https://minecraft.wiki/w/Mob and each mob's page.
 *
 * The tactics:
 * <ul>
 * <li>MELEE: fight it. Shield up between swings, crits, sprint-hits for knockback.</li>
 * <li>RANGED: close in with the shield raised toward it, or shoot back with a
 *     bow. Kill it before the melee mobs, since it keeps hitting from afar.</li>
 * <li>EXPLODES: the creeper. Shoot it at range. Up close, sprint-hit it once
 *     and back off before it comes in again. Once it hisses, run, and if it is
 *     too close to outrun, face it with the shield up: a raised shield blocks
 *     the blast. Never fight one in a tunnel with no room to back off.</li>
 * <li>GAZE: the enderman. Never look at its head; a calm one is left alone.
 *     One that is angry is fought with the shield up between swings. Arrows
 *     never hit it.</li>
 * <li>NEUTRAL: never hit first: hitting one turns it, and often its whole
 *     group, on the helper. Fought only once it attacks.</li>
 * <li>FLEE: never fight. The warden, the wither, raid leaders and the like are
 *     beyond any kit a trip carries: walk away and stay away.</li>
 * </ul>
 */
public final class Bestiary {

    public enum Tactic { MELEE, RANGED, EXPLODES, GAZE, NEUTRAL, FLEE }

    /**
     * One kind of mob. {@code damage} is per hit on normal difficulty, {@code every}
     * the ticks between hits, {@code climbs} true for mobs a pillar does not stop.
     */
    public record Kind(double health, double damage, int every, Tactic tactic, boolean climbs, String advice) {

        public double dps() {
            return damage * 20.0 / every;
        }
    }

    private static final Kind UNKNOWN = new Kind(20, 4, 20, Tactic.MELEE, false, "fight it carefully");
    private static final Map<EntityType, Kind> KINDS = new EnumMap<>(EntityType.class);
    /**
     * The share of a melee mob's hits that land in a real fight: sprint-hits
     * knock it back, and only a couple can stand in reach at once.
     */
    private static final double KNOCKED_BACK = 0.5;
    /** Spiders leave a player alone in light at least this bright. */
    private static final int SPIDER_CALM_LIGHT = 12;
    /** Damage and attacks per second for each weapon the fighter swings. */
    private static final Map<Material, double[]> WEAPONS = new EnumMap<>(Map.ofEntries(
        Map.entry(Material.NETHERITE_SWORD, new double[] {8, 1.6}),
        Map.entry(Material.DIAMOND_SWORD, new double[] {7, 1.6}),
        Map.entry(Material.IRON_SWORD, new double[] {6, 1.6}),
        Map.entry(Material.COPPER_SWORD, new double[] {5, 1.6}),
        Map.entry(Material.STONE_SWORD, new double[] {5, 1.6}),
        Map.entry(Material.WOODEN_SWORD, new double[] {4, 1.6}),
        Map.entry(Material.NETHERITE_AXE, new double[] {10, 1.0}),
        Map.entry(Material.DIAMOND_AXE, new double[] {9, 1.0}),
        Map.entry(Material.IRON_AXE, new double[] {9, 0.9}),
        Map.entry(Material.COPPER_AXE, new double[] {9, 0.8}),
        Map.entry(Material.STONE_AXE, new double[] {9, 0.8}),
        Map.entry(Material.WOODEN_AXE, new double[] {7, 0.8})));

    static {
        melee(EntityType.ZOMBIE, 20, 3, "fight it; babies are fast, so they go first");
        melee(EntityType.HUSK, 20, 3, "fight it; its hits make you hungry, so eat after");
        melee(EntityType.ZOMBIE_VILLAGER, 20, 3, "fight it");
        melee(EntityType.DROWNED, 20, 3, "fight it out of the water; one with a trident throws it, so shield up");
        melee(EntityType.ZOMBIE_NAUTILUS, 20, 3, "leave the water");
        climber(EntityType.SPIDER, 16, 2, "fight it; it climbs walls, so a pillar does not help");
        climber(EntityType.CAVE_SPIDER, 12, 2, "fight it fast and eat for the poison; break its spawner if in reach");
        melee(EntityType.SILVERFISH, 8, 1, "fight it; never break infested stone, since more come out");
        melee(EntityType.ENDERMITE, 8, 2, "fight it");
        melee(EntityType.SLIME, 16, 4, "fight it in the open; big ones split, small ones do no harm");
        melee(EntityType.MAGMA_CUBE, 16, 6, "fight it in the open; it splits and hits harder than a slime");
        melee(EntityType.SULFUR_CUBE, 16, 4, "fight it in the open");
        melee(EntityType.HOGLIN, 40, 6, "fight with the shield; it throws you, so keep away from edges");
        melee(EntityType.ZOGLIN, 40, 6, "fight with the shield; it throws you, so keep away from edges");
        melee(EntityType.WITHER_SKELETON, 20, 8, "fight with the shield up; its wither hurts after the hit");
        melee(EntityType.VINDICATOR, 24, 13, "shield up, it hits very hard; back off if hurt");
        melee(EntityType.PHANTOM, 20, 2, "get under a roof, or shield up and hit it as it swoops");
        melee(EntityType.CREAKING, 1000, 3, "look at it so it cannot move, and walk away; only its heart kills it");
        melee(EntityType.CAMEL_HUSK, 20, 3, "fight it");
        melee(EntityType.GIANT, 100, 50, "walk away");
        ranged(EntityType.SKELETON, 20, 4, 40, "close in with the shield up, or use cover");
        ranged(EntityType.STRAY, 20, 4, 40, "close in with the shield up; its arrows slow you");
        ranged(EntityType.BOGGED, 16, 4, 70, "close in with the shield up; its arrows poison");
        ranged(EntityType.PARCHED, 16, 4, 40, "close in with the shield up");
        ranged(EntityType.PILLAGER, 24, 4, 40, "close in with the shield up");
        ranged(EntityType.WITCH, 26, 6, 60, "rush it before it drinks a potion; eat for the poison");
        ranged(EntityType.BLAZE, 20, 5, 60, "shield up against the fireballs and close in");
        ranged(EntityType.GHAST, 10, 6, 60, "shield up or hit the fireball back");
        ranged(EntityType.SHULKER, 30, 4, 40, "shield up; its bullets make you float, then fall");
        ranged(EntityType.GUARDIAN, 30, 6, 60, "leave the water and break line of sight");
        ranged(EntityType.BREEZE, 30, 1, 40, "keep away from edges, its wind throws you; shield up");
        KINDS.put(EntityType.CREEPER, new Kind(20, 30, 30, Tactic.EXPLODES, false,
            "shoot it; up close hit once and back off; when it hisses run, or shield up facing it if too close"));
        KINDS.put(EntityType.ENDERMAN, new Kind(40, 7, 20, Tactic.GAZE, false,
            "never look at its head; if angry, shield up and fight it, arrows miss it"));
        neutral(EntityType.ZOMBIFIED_PIGLIN, 20, 8, "never hit one: the whole group turns on you");
        neutral(EntityType.PIGLIN, 16, 5, "wear gold, or leave it be");
        neutral(EntityType.IRON_GOLEM, 100, 15, "never hit it");
        neutral(EntityType.WOLF, 8, 4, "never hit one: the pack comes");
        neutral(EntityType.BEE, 10, 2, "never hit one: the hive comes");
        neutral(EntityType.POLAR_BEAR, 30, 6, "keep away from its cubs");
        neutral(EntityType.LLAMA, 22, 1, "leave it be");
        neutral(EntityType.TRADER_LLAMA, 22, 1, "leave it be");
        neutral(EntityType.PANDA, 20, 6, "leave it be");
        neutral(EntityType.GOAT, 10, 2, "keep away from edges near one");
        neutral(EntityType.DOLPHIN, 10, 3, "leave it be");
        neutral(EntityType.SNOW_GOLEM, 4, 0, "leave it be");
        neutral(EntityType.COPPER_GOLEM, 12, 0, "leave it be");
        flee(EntityType.WARDEN, 500, 30, "never fight it: walk away quietly and leave the deep dark");
        flee(EntityType.PIGLIN_BRUTE, 50, 13, "walk away, it does not stop");
        flee(EntityType.EVOKER, 24, 6, "walk away; its vexes go through walls");
        flee(EntityType.VEX, 14, 9, "walk away from the evoker that sent it");
        flee(EntityType.RAVAGER, 100, 12, "walk away");
        flee(EntityType.ILLUSIONER, 32, 4, "walk away");
        flee(EntityType.ELDER_GUARDIAN, 80, 8, "leave the water");
        flee(EntityType.WITHER, 300, 8, "walk away");
        flee(EntityType.ENDER_DRAGON, 200, 15, "walk away");
    }

    private Bestiary() {
    }

    private static void melee(EntityType type, double health, double damage, String advice) {
        KINDS.put(type, new Kind(health, damage, 20, Tactic.MELEE, false, advice));
    }

    private static void climber(EntityType type, double health, double damage, String advice) {
        KINDS.put(type, new Kind(health, damage, 20, Tactic.MELEE, true, advice));
    }

    private static void ranged(EntityType type, double health, double damage, int every, String advice) {
        KINDS.put(type, new Kind(health, damage, every, Tactic.RANGED, true, advice));
    }

    private static void neutral(EntityType type, double health, double damage, String advice) {
        KINDS.put(type, new Kind(health, damage, 20, Tactic.NEUTRAL, false, advice));
    }

    private static void flee(EntityType type, double health, double damage, String advice) {
        KINDS.put(type, new Kind(health, damage, 20, Tactic.FLEE, true, advice));
    }

    public static Kind of(EntityType type) {
        return KINDS.getOrDefault(type, UNKNOWN);
    }

    /** Slimes and magma cubes are as strong as they are big. */
    public static Kind of(Entity entity) {
        Kind kind = of(entity.getType());
        if (entity instanceof Slime slime) {
            int size = slime.getSize();
            double damage = size <= 1 ? (entity instanceof MagmaCube ? 3 : 0) : size * (entity instanceof MagmaCube ? 1.5 : 1);
            return new Kind(size * size, damage, 20, Tactic.MELEE, false, kind.advice());
        }
        return kind;
    }

    /**
     * The mob is after the helper now: it targets the helper, or it is a
     * hostile that does not wait to be hit. Calm endermen, neutral mobs, and
     * spiders in daylight leave a player alone until something sets them off.
     */
    public static boolean hostileTo(Player self, Entity entity) {
        if (!(entity instanceof LivingEntity living) || living.isDead()) {
            return false;
        }
        boolean targeting = entity instanceof Mob mob && mob.getTarget() != null
            && mob.getTarget().getUniqueId().equals(self.getUniqueId());
        if (targeting) {
            return true;
        }
        Tactic tactic = of(entity).tactic();
        if (tactic == Tactic.GAZE || tactic == Tactic.NEUTRAL || entity instanceof Enderman
            || entity instanceof PigZombie) {
            return false;
        }
        if (entity instanceof Spider) {
            return entity.getLocation().getBlock().getLightLevel() < SPIDER_CALM_LIGHT;
        }
        return entity instanceof org.bukkit.entity.Enemy;
    }

    /** One fighter's numbers: hits per second times damage, and the damage it can take. */
    public record Strength(double dps, double toughness) {
    }

    /** What the helper brings to a fight right now: its best weapon, armor worn, health, and shield. Main thread. */
    public static Strength strength(Player self) {
        double dps = 4;
        for (Map.Entry<Material, double[]> weapon : WEAPONS.entrySet()) {
            if (self.getInventory().contains(weapon.getKey())) {
                dps = Math.max(dps, weapon.getValue()[0] * weapon.getValue()[1]);
            }
        }
        double armor = value(self, Attribute.ARMOR, 0);
        double toughness = value(self, Attribute.ARMOR_TOUGHNESS, 0);
        double typical = 4;
        double reduction = Math.min(20, Math.max(armor / 5, armor - typical / (2 + toughness / 4))) / 25;
        boolean shield = self.getInventory().contains(Material.SHIELD)
            || self.getInventory().getItemInOffHand().getType() == Material.SHIELD;
        double taken = (1 - reduction) * (shield ? 0.6 : 1);
        return new Strength(dps, self.getHealth() / taken);
    }

    private static double value(Player self, Attribute attribute, double fallback) {
        AttributeInstance instance = self.getAttribute(attribute);
        return instance == null ? fallback : instance.getValue();
    }

    /**
     * Whether the helper wins a fight against all of {@code foes} at once,
     * killing them in the order given: the damage it takes is each foe's damage
     * per second for as long as that foe is still alive, half of it for melee
     * mobs that get knocked back. A creeper counts as the part of its blast that
     * gets through on average; less with a shield. It must end the fight with at
     * least a third of what it can take to spare.
     */
    public static boolean wins(Strength self, List<Foe> foes, boolean shield) {
        double clock = 0;
        double taken = 0;
        for (Foe foe : foes) {
            Kind kind = foe.kind();
            if (kind.tactic() == Tactic.FLEE) {
                return false;
            }
            clock += foe.health() / Math.max(0.5, self.dps()) + 1;
            taken += switch (kind.tactic()) {
                case EXPLODES -> kind.damage() * (shield ? 0.1 : 0.35);
                case RANGED -> kind.dps() * clock;
                default -> kind.dps() * KNOCKED_BACK * clock;
            };
        }
        return taken <= self.toughness() * 2 / 3;
    }

    /** One mob in a fight: what kind it is and the health it has left. */
    public record Foe(Kind kind, double health) {

        public static Foe of(LivingEntity entity) {
            return new Foe(Bestiary.of(entity), entity.getHealth());
        }
    }

    /**
     * Who to deal with first: a creeper that is close, then whatever hits from
     * afar or makes others stronger, then the nearest. Main thread.
     */
    public static List<LivingEntity> order(Player self, List<LivingEntity> foes) {
        Location at = self.getLocation();
        List<LivingEntity> sorted = new ArrayList<>(foes);
        sorted.sort(Comparator.comparingDouble(foe -> urgency(foe, at.distance(foe.getLocation()))));
        return sorted;
    }

    private static double urgency(LivingEntity foe, double distance) {
        Kind kind = of(foe);
        double bonus = switch (kind.tactic()) {
            case EXPLODES -> distance <= 6 ? 12 : 0;
            case RANGED -> foe.getType() == EntityType.WITCH ? 6 : 3;
            default -> foe instanceof org.bukkit.entity.Ageable ageable && !ageable.isAdult() ? 2 : 0;
        };
        if (foe instanceof Creeper creeper && creeper.getFuseTicks() > 0) {
            bonus += 20;
        }
        return distance - bonus;
    }

    /** The mobs near the helper, counted by kind with the nearest distance, for briefings. Main thread. */
    public static String nearby(Player self, double radius) {
        Location at = self.getLocation();
        Map<String, double[]> seen = new TreeMap<>();
        for (Entity entity : self.getNearbyEntities(radius, radius, radius)) {
            if (!(entity instanceof Mob) || !KINDS.containsKey(entity.getType())) {
                continue;
            }
            String name = entity.getType().getKey().value() + (hostileTo(self, entity) ? " (after me)" : "");
            double distance = at.distance(entity.getLocation());
            seen.merge(name, new double[] {1, distance}, (a, b) -> new double[] {a[0] + 1, Math.min(a[1], b[1])});
        }
        if (seen.isEmpty()) {
            return "no mobs near";
        }
        List<String> parts = new ArrayList<>();
        seen.forEach((name, counted) -> parts.add((int) counted[0] + " " + name + " " + Math.round(counted[1]) + "m"));
        return "mobs near: " + String.join(", ", parts);
    }
}
