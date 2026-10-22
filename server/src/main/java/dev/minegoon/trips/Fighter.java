package dev.minegoon.trips;

import dev.minegoon.actions.Body;
import dev.minegoon.actions.Checkpoint;
import dev.minegoon.actions.Hands;
import dev.minegoon.actions.Mover;
import dev.minegoon.actions.Placer;
import dev.minegoon.spawn.FakePlayer;
import dev.minegoon.spawn.FakePlayer.Controls;
import dev.minegoon.world.Blocks;
import dev.minegoon.world.Goal;
import dev.minegoon.world.Pos;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/**
 * Melee fighting with the game's own physics. Every tick it aims, keeps its
 * distance, and swings only at full charge, the way strong players do:
 * <ul>
 * <li>Critical hits come from a real jump, landing the swing on the way down.</li>
 * <li>Otherwise it hits while sprinting for extra knockback, and sprints again
 *     straight after (a w-tap), so every grounded hit pushes.</li>
 * <li>While its sword recharges it strafes sideways inside 2.2 to 2.8 blocks.</li>
 * <li>The shield goes up against a drawn bow and between swings up close.</li>
 * <li>A creeper is hit once and backed away from, so it never swells in reach.
 *     One that hisses is outrun, or met with the shield up when too close.</li>
 * <li>A monster that a ledge or wall keeps out of reach is walked round to.</li>
 * <li>Calm endermen and neutral mobs are never started on; see {@link Bestiary}.</li>
 * </ul>
 * Timings follow https://minecraft.wiki/w/Melee_attack and /w/Shield.
 */
public final class Fighter {

    private static final double ENGAGE = 12;
    private static final double CLOSE = 4;
    private static final double REACH = 3.0;
    private static final double BAND_LOW = 2.2;
    private static final double BAND_HIGH = 2.8;
    private static final float READY = 0.9f;
    private static final float JUMP_AT = 0.55f;
    private static final double CREEPER_SAFE = 5;
    private static final double FLEE_HEALTH = 6;
    /** How far ahead a move is checked for a ledge or lava, and the drop a fight may step down. */
    private static final double PROBE = 0.8;
    private static final int FIGHT_DROP = 3;
    /** Closer than this a bow is put away for the sword. */
    private static final double BOW_MIN = 6;
    private static final int FULL_DRAW = 20;
    /** Ticks without getting closer before a monster counts as out of reach, worth a bow. */
    private static final int OUT_OF_REACH_TICKS = 60;
    private static final int MAX_TICKS = 20 * 90;
    private static final int STALLED_TICKS = 20 * 10;
    private static final double LOST = 32;
    private static final int MAX_FIGHTS = 8;
    /** Walks round to a monster the fight cannot reach straight on, before letting it go. */
    private static final int APPROACHES = 3;
    private static final double APPROACH_RANGE = 2;
    /** How far round the helper a group is counted before a fight. */
    private static final double GROUP = 16;
    /** How far a getaway goes, and how far every pursuer must be before it stops. */
    private static final int RUN = 24;
    private static final double CLEAR = 14;
    private static final int PILLAR = 2;
    private static final int HOLD_TICKS = 20 * 60;
    private static final int QUIET_TICKS = 20 * 3;
    /** Inside this, a hissing creeper cannot be outrun before it blows, so the shield faces it instead. */
    private static final double BLAST_CLOSE = 3.5;
    /** Ticks a creeper is backed away from after each hit, so it never gets a swell going in reach. */
    private static final int CREEPER_BACKOFF = 12;
    private static final List<Material> WEAPONS = List.of(
        Material.NETHERITE_SWORD, Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.STONE_SWORD,
        Material.GOLDEN_SWORD, Material.WOODEN_SWORD, Material.NETHERITE_AXE, Material.DIAMOND_AXE,
        Material.IRON_AXE, Material.STONE_AXE, Material.WOODEN_AXE);

    /** How one fight went, for practice runs to score. */
    public record Tally(String target, boolean won, boolean died, boolean fled, int ticks, int swings, int hits,
                        int crits, int shots, double dealt, double taken) {
    }

    private final Body body;
    private final Mover mover;
    private volatile boolean engaged;

    public Fighter(Body body, Mover mover) {
        this.body = body;
        this.mover = mover;
    }

    /** True while a fight has the helper's body. Reflexes stay out of the way. */
    public boolean engaged() {
        return engaged;
    }

    /**
     * Fights whatever hostile is coming for the helper, most urgent first, until
     * none is. Before each fight it weighs the whole group against itself, and
     * a group it would not beat with a third of its health to spare is not
     * fought: against melee mobs that cannot climb it stands on a two-block
     * pillar and hits down at them; against anything else it gets away.
     * Worker thread.
     */
    public void defend() {
        for (int fights = 0; fights < MAX_FIGHTS; fights++) {
            Odds odds = body.main().call(this::sizeUp);
            if (odds == null) {
                return;
            }
            if (!odds.wins()) {
                escape(odds);
                return;
            }
            Tally tally = fight(odds.first());
            if (tally.died() || tally.fled() || !tally.won() && tally.hits() == 0) {
                return;
            }
        }
    }

    /** The group after the helper, most urgent first, and whether fighting all of it is a win. */
    private record Odds(List<UUID> foes, boolean wins, boolean pillar, Vector away, String names) {
        UUID first() {
            return foes.getFirst();
        }
    }

    /** Null when nothing is after the helper. Main thread. */
    private Odds sizeUp() {
        Player self = body.helper().player();
        if (self == null || self.isDead()) {
            return null;
        }
        Location at = self.getLocation();
        List<LivingEntity> foes = self.getNearbyEntities(GROUP, GROUP / 2, GROUP).stream()
            .filter(entity -> entity instanceof LivingEntity living && !living.isDead())
            .map(LivingEntity.class::cast)
            .filter(foe -> Bestiary.hostileTo(self, foe)
                && (huntingMe(self, foe) || foe.getLocation().distance(at) <= CLOSE))
            .filter(self::hasLineOfSight)
            .toList();
        if (foes.isEmpty()) {
            return null;
        }
        List<LivingEntity> ordered = Bestiary.order(self, foes);
        boolean shield = self.getInventory().contains(Material.SHIELD)
            || self.getInventory().getItemInOffHand().getType() == Material.SHIELD;
        boolean wins = Bestiary.wins(Bestiary.strength(self),
            ordered.stream().map(Bestiary.Foe::of).toList(), shield);
        boolean pillar = Placer.throwaways(self) >= 2 && !self.isInWater() && ordered.stream().noneMatch(foe -> {
            Bestiary.Kind kind = Bestiary.of(foe);
            return kind.climbs() || kind.tactic() != Bestiary.Tactic.MELEE;
        });
        Vector away = new Vector();
        for (LivingEntity foe : ordered) {
            away.add(at.toVector().subtract(foe.getLocation().toVector()).setY(0).normalize());
        }
        String names = ordered.stream().map(foe -> foe.getType().getKey().value()).distinct()
            .reduce((a, b) -> a + ", " + b).orElse("");
        return new Odds(ordered.stream().map(Entity::getUniqueId).toList(), wins, pillar, away, names);
    }

    /**
     * Too much to fight. A pillar two blocks up keeps zombies and the like
     * from reaching, and it hits them from there; otherwise it walks away
     * from the group until none is close. Worker thread.
     */
    private void escape(Odds odds) {
        body.helper().say("Too much for me here (" + odds.names() + "), so I'll "
            + (odds.pillar() ? "get up out of reach." : "back off."));
        if (odds.pillar() && pillarUp()) {
            holdHigh();
            return;
        }
        Pos feet = mover.feet();
        Vector away = odds.away().lengthSquared() < 1e-6 ? new Vector(1, 0, 0) : odds.away().normalize();
        int dirX = (int) Math.round(away.getX());
        int dirZ = (int) Math.round(away.getZ());
        if (dirX == 0 && dirZ == 0) {
            dirX = 1;
        }
        try {
            mover.walk(Goal.away(feet, dirX, dirZ, RUN), () -> {
                if (body.main().call(this::clear)) {
                    throw new Close();
                }
            });
        } catch (Close clear) {
            // Far enough from all of them.
        }
    }

    /** Nothing after the helper within {@link #CLEAR} blocks. Main thread. */
    private boolean clear() {
        Player self = body.helper().player();
        return self == null || self.getNearbyEntities(CLEAR, CLEAR / 2, CLEAR).stream()
            .noneMatch(entity -> Bestiary.hostileTo(self, entity) && huntingMe(self, entity));
    }

    private boolean pillarUp() {
        Pos feet = mover.feet();
        return mover.walk(Goal.near(feet.offset(0, PILLAR, 0), 0.5), Checkpoint.NONE);
    }

    /**
     * On a pillar: stands still with sneak held so it never steps off, hits
     * whatever comes within reach below, and keeps the shield up between
     * swings. Done when nothing has been after it for a few seconds.
     */
    private void holdHigh() {
        engaged = true;
        try {
            int quiet = 0;
            for (int tick = 0; tick < HOLD_TICKS && quiet < QUIET_TICKS; tick++) {
                boolean busy = body.main().tick(() -> {
                    Player self = body.helper().player();
                    if (self == null || self.isDead()) {
                        return false;
                    }
                    arm(self);
                    body.helper().drive(new Controls(0, 0, false, false, true));
                    Optional<LivingEntity> near = self.getNearbyEntities(REACH + 1, REACH + 2, REACH + 1).stream()
                        .filter(entity -> entity instanceof LivingEntity living && !living.isDead()
                            && Bestiary.hostileTo(self, entity))
                        .map(LivingEntity.class::cast)
                        .filter(foe -> reach(self, foe) <= REACH && self.hasLineOfSight(foe))
                        .min(Comparator.comparingDouble(foe -> reach(self, foe)));
                    if (near.isPresent()) {
                        LivingEntity foe = near.get();
                        Location eye = self.getEyeLocation();
                        Vector to = foe.getBoundingBox().getCenter().subtract(eye.toVector());
                        FakePlayer.face(self, (float) Math.toDegrees(Math.atan2(-to.getX(), to.getZ())),
                            (float) -Math.toDegrees(Math.atan2(to.getY(), Math.hypot(to.getX(), to.getZ()))));
                        if (self.getAttackCooldown() >= READY) {
                            lowerShield(self);
                            self.attack(foe);
                            self.swingMainHand();
                        } else if (self.getInventory().getItemInOffHand().getType() == Material.SHIELD
                            && !self.isHandRaised()) {
                            self.startUsingItem(EquipmentSlot.OFF_HAND);
                        }
                    }
                    return !clear();
                });
                quiet = busy ? 0 : quiet + 1;
            }
        } finally {
            body.main().run(() -> {
                Player self = body.helper().player();
                if (self != null) {
                    lowerShield(self);
                }
            });
            engaged = false;
        }
    }

    /** Fights one creature until it dies, the helper dies or flees, or it gets away. Worker thread. */
    public Tally fight(UUID target) {
        Duel duel = body.main().call(() -> new Duel(target));
        engaged = true;
        try {
            int approaches = 0;
            for (Beat beat = body.main().tick(duel::tick); beat != Beat.OVER; beat = body.main().tick(duel::tick)) {
                if (beat == Beat.CLOSE_IN && (++approaches > APPROACHES || !closeIn(duel))) {
                    break;
                }
            }
        } finally {
            body.main().run(duel::end);
            engaged = false;
        }
        return duel.tally();
    }

    /**
     * Walks a planned route toward a monster that a ledge or a wall keeps out of
     * reach, and is back in the fight as soon as it is close. Reflexes have the
     * body on the way. False when there is no way there. Worker thread.
     */
    private boolean closeIn(Duel duel) {
        Pos at = body.main().call(duel::enemyFeet);
        if (at == null) {
            return true;
        }
        engaged = false;
        try {
            return mover.walk(Goal.near(at, APPROACH_RANGE), () -> {
                if (body.main().call(duel::close)) {
                    throw new Close();
                }
            });
        } catch (Close close) {
            return true;
        } finally {
            engaged = true;
        }
    }

    /** Ends a walk toward a monster once it is close enough to fight. */
    private static final class Close extends RuntimeException {
        Close() {
            super(null, null, false, false);
        }
    }

    /** What a fight does after a tick: carry on, walk round to the monster, or stop. */
    private enum Beat { ON, CLOSE_IN, OVER }

    private static boolean huntingMe(Player self, Entity entity) {
        return entity instanceof Mob mob && mob.getTarget() != null
            && mob.getTarget().getUniqueId().equals(self.getUniqueId());
    }

    /** One fight's state. Every method runs on the main thread. */
    private final class Duel {
        private final UUID target;
        private final String name;
        private int ticks;
        private int swings;
        private int hits;
        private int crits;
        private int shots;
        private int drawn;
        private double dealt;
        private double taken;
        private double lastHealth;
        private double enemyHealth = Double.NaN;
        private double closest = Double.MAX_VALUE;
        private int sinceCloser;
        private int strafe = ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
        private int strafeLeft = strafeTime();
        private int backoff;
        private boolean won;
        private boolean died;
        private boolean fled;

        Duel(UUID target) {
            this.target = target;
            Entity entity = Bukkit.getEntity(target);
            this.name = entity == null ? "nothing" : entity.getType().getKey().value();
            Player self = body.player();
            this.lastHealth = self.getHealth();
            arm(self);
        }

        Beat tick() {
            Player self = body.helper().player();
            if (self == null || self.isDead()) {
                died = true;
                return Beat.OVER;
            }
            taken += Math.max(0, lastHealth - self.getHealth());
            lastHealth = self.getHealth();
            if (!(Bukkit.getEntity(target) instanceof LivingEntity enemy) || enemy.isDead() || !enemy.isValid()) {
                won = true;
                return Beat.OVER;
            }
            if (enemy.getHealth() < enemyHealth) {
                dealt += enemyHealth - enemy.getHealth();
            }
            enemyHealth = enemy.getHealth();
            double reach = reach(self, enemy);
            if (++ticks > MAX_TICKS || reach > LOST) {
                return Beat.OVER;
            }
            if (stalled(reach) && !canShoot(self, enemy)) {
                closest = Double.MAX_VALUE;
                sinceCloser = 0;
                return Beat.CLOSE_IN;
            }
            if (self.getHealth() <= FLEE_HEALTH && !(enemy instanceof Creeper)) {
                return flee(self, enemy) ? Beat.ON : Beat.OVER;
            }
            aim(self, enemy);
            boolean ready = self.getAttackCooldown() >= READY;
            boolean inReach = reach <= REACH;

            if (shoot(self, enemy, reach)) {
                return Beat.ON;
            }
            if (enemy instanceof Creeper creeper) {
                if (creeper.getFuseTicks() > 0) {
                    if (reach < BLAST_CLOSE && raiseShield(self)) {
                        body.helper().drive(steer(self, new Controls(-1, 0, false, false)));
                        return Beat.ON;
                    }
                    return flee(self, enemy) ? Beat.ON : Beat.OVER;
                }
                if (backoff > 0) {
                    backoff--;
                    lowerShield(self);
                    body.helper().drive(steer(self, new Controls(-1, strafe, false, false)));
                    return Beat.ON;
                }
            }
            weapon(self, enemy);
            if (inReach && ready) {
                hit(self, enemy, !(enemy instanceof Creeper) && FakePlayer.falling(self));
                if (enemy instanceof Creeper) {
                    backoff = CREEPER_BACKOFF;
                }
            }
            guard(self, enemy, reach, ready);
            body.helper().drive(steer(self, move(self, enemy, reach, ready)));
            return Beat.ON;
        }

        /** Where the monster stands, or null when it is gone. */
        Pos enemyFeet() {
            return Bukkit.getEntity(target) instanceof LivingEntity enemy && !enemy.isDead()
                ? Pos.standing(enemy.getLocation()) : null;
        }

        /** The monster is gone, or near enough to fight straight on. */
        boolean close() {
            Player self = body.helper().player();
            return self == null || !(Bukkit.getEntity(target) instanceof LivingEntity enemy) || enemy.isDead()
                || reach(self, enemy) <= CLOSE;
        }

        /**
         * Against a skeleton or a creeper at range, or anything out of reach, draws
         * a bow to full and looses with the drop of the arrow allowed for, once the
         * arrow's whole flight is clear. False when the fight is a sword fight this tick.
         */
        private boolean shoot(Player self, LivingEntity enemy, double reach) {
            boolean ranged = canShoot(self, enemy)
                && (reach >= BOW_MIN && (enemy instanceof AbstractSkeleton || enemy instanceof Creeper)
                    || reach > REACH && sinceCloser > OUT_OF_REACH_TICKS);
            if (!ranged) {
                if (self.getInventory().getItemInMainHand().getType() == Material.BOW) {
                    self.clearActiveItem();
                    drawn = 0;
                    arm(self);
                }
                return false;
            }
            if (self.getInventory().getItemInMainHand().getType() != Material.BOW) {
                lowerShield(self);
                Hands.hold(self, Material.BOW);
            }
            if (!self.hasActiveItem()) {
                self.startUsingItem(EquipmentSlot.HAND);
                drawn = 0;
            }
            Location eye = self.getEyeLocation();
            Vector to = enemy.getBoundingBox().getCenter().subtract(eye.toVector());
            double flight = Ballistics.ticks(Math.hypot(to.getX(), to.getZ()));
            to.add(enemy.getVelocity().clone().setY(0).multiply(flight));
            double distance = Math.hypot(to.getX(), to.getZ());
            double yaw = Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            double pitch = Ballistics.pitch(distance, to.getY());
            FakePlayer.face(self, (float) yaw, (float) pitch);
            if (++drawn >= FULL_DRAW && clearShot(self, eye, yaw, pitch, distance)) {
                FakePlayer.release(self);
                shots++;
                drawn = 0;
            }
            body.helper().drive(steer(self, new Controls(0, strafe, false, false)));
            return true;
        }

        private static boolean canShoot(Player self, LivingEntity enemy) {
            return self.getInventory().contains(Material.BOW) && self.getInventory().contains(Material.ARROW)
                && self.hasLineOfSight(enemy);
        }

        /** An axe against a raised shield, since it knocks the shield out for five seconds; otherwise the sword. */
        private void weapon(Player self, LivingEntity enemy) {
            Material held = self.getInventory().getItemInMainHand().getType();
            boolean shielded = enemy instanceof HumanEntity human && human.isBlocking();
            if (!shielded && Tag.ITEMS_AXES.isTagged(held)) {
                arm(self);
                return;
            }
            if (!shielded || Tag.ITEMS_AXES.isTagged(held)) {
                return;
            }
            for (Material axe : WEAPONS) {
                if (Tag.ITEMS_AXES.isTagged(axe) && Hands.hold(self, axe)) {
                    return;
                }
            }
        }

        /** Drops the parts of a move that would take the helper off a ledge or into lava; strafes the other way instead. */
        private Controls steer(Player self, Controls wanted) {
            float side = wanted.strafe();
            if (side != 0 && !safe(self, 0, side)) {
                side = safe(self, 0, -side) ? -side : 0;
                strafe = side > 0 ? 1 : side < 0 ? -1 : strafe;
            }
            float forward = wanted.forward() != 0 && !safe(self, wanted.forward(), 0) ? 0 : wanted.forward();
            return new Controls(forward, side, wanted.jump(), wanted.sprint() && forward > 0);
        }

        /** A crit when falling; otherwise a sprinting hit for knockback. */
        private void hit(Player self, LivingEntity enemy, boolean crit) {
            lowerShield(self);
            self.setSprinting(!crit && self.getFoodLevel() > 6);
            double before = enemy.getHealth();
            self.attack(enemy);
            self.swingMainHand();
            swings++;
            double done = before - (enemy.isDead() ? 0 : enemy.getHealth());
            if (done > 0) {
                hits++;
                dealt += done;
                crits += crit ? 1 : 0;
            }
            enemyHealth = enemy.getHealth();
        }

        /** Shield up against a drawn bow, and up close while the sword recharges. */
        private void guard(Player self, LivingEntity enemy, double reach, boolean ready) {
            if (self.getInventory().getItemInOffHand().getType() != Material.SHIELD) {
                return;
            }
            boolean arrow = enemy instanceof AbstractSkeleton && enemy.isHandRaised() && reach > REACH;
            boolean melee = !(enemy instanceof AbstractSkeleton) && !(enemy instanceof Creeper)
                && reach < BAND_HIGH && !ready && FakePlayer.grounded(self);
            if (arrow || melee) {
                if (!self.isHandRaised()) {
                    self.startUsingItem(EquipmentSlot.OFF_HAND);
                }
            } else {
                lowerShield(self);
            }
        }

        /**
         * Closes in when far, backs off when too close, strafes in between. Jumps
         * just before the sword is charged so the next swing lands on the way down.
         */
        private Controls move(Player self, LivingEntity enemy, double reach, boolean ready) {
            if (--strafeLeft <= 0) {
                strafe = -strafe;
                strafeLeft = strafeTime();
            }
            boolean blocked = FakePlayer.bumped(self) && FakePlayer.grounded(self);
            boolean shielding = self.isHandRaised();
            if (!ready && self.hasCooldown(Material.SHIELD) && reach < REACH + 1) {
                return new Controls(-1, strafe, blocked, false);
            }
            if (reach > BAND_HIGH) {
                float side = enemy instanceof AbstractSkeleton ? strafe : 0;
                return new Controls(1, side, blocked || critJump(self, enemy, reach), !shielding);
            }
            if (reach < BAND_LOW) {
                return new Controls(-0.6f, strafe, blocked, false);
            }
            return new Controls(0, strafe, blocked || critJump(self, enemy, reach), false);
        }

        private boolean critJump(Player self, LivingEntity enemy, double reach) {
            return !(enemy instanceof Creeper) && reach <= REACH + 0.5 && FakePlayer.grounded(self)
                && self.getAttackCooldown() >= JUMP_AT && self.getAttackCooldown() < READY && headroom(self);
        }

        private boolean flee(Player self, LivingEntity enemy) {
            lowerShield(self);
            Vector away = self.getLocation().toVector().subtract(enemy.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 1e-6) {
                away = new Vector(1, 0, 0);
            }
            float yaw = (float) Math.toDegrees(Math.atan2(-away.getX(), away.getZ()));
            FakePlayer.face(self, yaw, 0);
            body.helper().drive(steer(self, new Controls(1, 0, FakePlayer.bumped(self) && FakePlayer.grounded(self), true)));
            if (reach(self, enemy) > ENGAGE) {
                fled = true;
                return false;
            }
            return true;
        }

        /** Turns to the enemy's chest, at once. */
        private void aim(Player self, LivingEntity enemy) {
            Location eye = self.getEyeLocation();
            BoundingBox box = enemy.getBoundingBox();
            Vector to = box.getCenter().subtract(eye.toVector());
            double wantYaw = Math.toDegrees(Math.atan2(-to.getX(), to.getZ()));
            double wantPitch = -Math.toDegrees(Math.atan2(to.getY(), Math.hypot(to.getX(), to.getZ())));
            FakePlayer.face(self, (float) wantYaw, (float) wantPitch);
        }

        private boolean stalled(double reach) {
            if (reach < closest - 0.25) {
                closest = reach;
                sinceCloser = 0;
                return false;
            }
            return reach > REACH && ++sinceCloser > STALLED_TICKS;
        }

        void end() {
            Player self = body.helper().player();
            if (self != null && !self.isDead()) {
                lowerShield(self);
                self.setSprinting(false);
            }
        }

        Tally tally() {
            return new Tally(name, won, died, fled, ticks, swings, hits, crits, shots, dealt, taken);
        }
    }

    /** The best weapon in hand and a shield in the off hand, if the helper has them. */
    private static void arm(Player self) {
        for (Material weapon : WEAPONS) {
            if (Hands.hold(self, weapon)) {
                break;
            }
        }
        PlayerInventory inventory = self.getInventory();
        if (inventory.getItemInOffHand().getType() == Material.SHIELD) {
            return;
        }
        int slot = inventory.first(Material.SHIELD);
        if (slot >= 0) {
            ItemStack shield = inventory.getItem(slot);
            inventory.setItem(slot, inventory.getItemInOffHand());
            inventory.setItemInOffHand(shield);
        }
    }

    /** Shield up in the off hand, toward wherever the eyes face. False when there is no shield. */
    static boolean raiseShield(Player self) {
        arm(self);
        if (self.getInventory().getItemInOffHand().getType() != Material.SHIELD) {
            return false;
        }
        if (!self.isHandRaised()) {
            self.startUsingItem(EquipmentSlot.OFF_HAND);
        }
        return true;
    }

    static void lowerShield(Player self) {
        if (self.isHandRaised()) {
            self.clearActiveItem();
        }
    }

    /** Reach is measured from the eyes to the nearest point of the target's box, as the game does. */
    private static double reach(Player self, LivingEntity enemy) {
        Vector eye = self.getEyeLocation().toVector();
        BoundingBox box = enemy.getBoundingBox();
        double dx = Math.max(0, Math.max(box.getMinX() - eye.getX(), eye.getX() - box.getMaxX()));
        double dy = Math.max(0, Math.max(box.getMinY() - eye.getY(), eye.getY() - box.getMaxY()));
        double dz = Math.max(0, Math.max(box.getMinZ() - eye.getZ(), eye.getZ() - box.getMaxZ()));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Nothing solid in the way of an arrow loosed this way, over its whole curve. */
    private static boolean clearShot(Player self, Location eye, double yaw, double pitch, double distance) {
        World world = self.getWorld();
        double dx = -Math.sin(Math.toRadians(yaw));
        double dz = Math.cos(Math.toRadians(yaw));
        return Ballistics.clear(Math.toRadians(-pitch), distance, (along, height) -> Blocks.passable(world.getType(
            (int) Math.floor(eye.getX() + dx * along), (int) Math.floor(eye.getY() + height),
            (int) Math.floor(eye.getZ() + dz * along))));
    }

    /** The ground a step that way lands on is there, at most {@link #FIGHT_DROP} down, and not burning. */
    private static boolean safe(Player self, float forward, float strafe) {
        Location at = self.getLocation();
        double yaw = Math.toRadians(at.getYaw());
        double dx = strafe * Math.cos(yaw) - forward * Math.sin(yaw);
        double dz = forward * Math.cos(yaw) + strafe * Math.sin(yaw);
        double length = Math.hypot(dx, dz);
        if (length == 0) {
            return true;
        }
        World world = self.getWorld();
        int x = (int) Math.floor(at.getX() + dx / length * PROBE);
        int z = (int) Math.floor(at.getZ() + dz / length * PROBE);
        int y = at.getBlockY();
        if (Blocks.harmful(world.getType(x, y, z)) || Blocks.harmful(world.getType(x, y + 1, z))) {
            return false;
        }
        for (int down = 1; down <= FIGHT_DROP + 1; down++) {
            Material below = world.getType(x, y - down, z);
            if (Blocks.harmful(below)) {
                return false;
            }
            if (!Blocks.passable(below) || Blocks.water(below)) {
                return true;
            }
        }
        return false;
    }

    private static boolean headroom(Player self) {
        Location at = self.getLocation();
        return Blocks.passable(at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() + 2, at.getBlockZ()).getType());
    }

    private static int strafeTime() {
        return ThreadLocalRandom.current().nextInt(12, 28);
    }
}
