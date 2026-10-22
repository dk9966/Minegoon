package dev.minegoon.world;

import dev.minegoon.MainThread;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;
import net.minecraft.world.level.block.Block;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;

/**
 * Everything the helper can see from where it stands: any block or animal in
 * render distance with a clear line from its eyes, in every direction, as if
 * it kept turning its head. No x-ray: a block behind other blocks is not seen.
 *
 * The world copy is kept for a few seconds and only chunks that are new or old
 * are copied again. Block searches read a chunk slice only when its palette says
 * the block may be there, so most of the world is never walked.
 */
public final class Vision {

    private static final long FRESH_MS = 8_000;
    private static final int MAX_LOOKS = 4_000;
    /**
     * Squared distance within which eyes in daylight still make out a dark block.
     * Further dark blocks are cave insides the sky never reaches, which cannot be
     * seen from outside and would only waste looks.
     */
    private static final long DARK_SIGHT = 16 * 16;
    private static final int CLIFF = 5;
    private static final int CAVE_DEPTH = 40;
    private static final int SHAFT_WALL = 6;
    private static final int[][] AROUND = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final MainThread main;
    private final Supplier<Player> player;
    private final int radius;
    private final Map<Long, Copy> copies = new HashMap<>();
    private String copiedWorld = "";

    private record Copy(ChunkSnapshot snapshot, long at) {
    }

    /** A cliff face the helper can see, and an open spot at its foot to stand on. */
    public record Cliff(Pos foot, Pos face, int height) {
    }

    /** A biome seen at the surface, at its nearest sampled point. */
    public record Patch(String biome, Pos at) {
    }

    public Vision(MainThread main, Supplier<Player> player, int radius) {
        this.main = main;
        this.player = player;
        this.radius = radius;
    }

    public int radius() {
        return radius;
    }

    /** A copy of the loaded world within render distance. Worker thread. */
    public WorldView around() {
        World world = main.call(() -> player.get().getWorld());
        Pos feet = feet();
        if (!world.getName().equals(copiedWorld)) {
            copies.clear();
            copiedWorld = world.getName();
        }
        long now = System.currentTimeMillis();
        int reach = (radius >> 4) + 1;
        Set<Long> inRange = new HashSet<>();
        List<Long> stale = new ArrayList<>();
        for (int cx = (feet.x() >> 4) - reach; cx <= (feet.x() >> 4) + reach; cx++) {
            for (int cz = (feet.z() >> 4) - reach; cz <= (feet.z() >> 4) + reach; cz++) {
                long key = WorldView.key(cx, cz);
                inRange.add(key);
                Copy copy = copies.get(key);
                if (copy == null || now - copy.at() > FRESH_MS) {
                    stale.add(key);
                }
            }
        }
        copies.keySet().retainAll(inRange);
        WorldView.copy(main, world, stale, true).forEach((key, snapshot) -> copies.put(key, new Copy(snapshot, now)));
        Map<Long, ChunkSnapshot> chunks = new HashMap<>();
        copies.forEach((key, copy) -> chunks.put(key, copy.snapshot()));
        return WorldView.of(world, chunks);
    }

    /** Forget the copy of the chunks around a spot, after the helper changed them. */
    public void touched(Pos pos) {
        copies.remove(WorldView.key(pos.x() >> 4, pos.z() >> 4));
    }

    /**
     * Blocks of a kind the helper can see, nearest first. {@code accept} can turn
     * down a block it should not take, such as one inside a protected area.
     */
    public List<Pos> blocks(Set<Material> wanted, int limit, Predicate<Candidate> accept) {
        if (wanted.isEmpty()) {
            return List.of();
        }
        WorldView view = around();
        Pos feet = feet();
        Set<Block> palette = Sections.blocks(wanted);
        List<Long> keys = new ArrayList<>();
        for (int cx = (feet.x() - radius) >> 4; cx <= (feet.x() + radius) >> 4; cx++) {
            for (int cz = (feet.z() - radius) >> 4; cz <= (feet.z() + radius) >> 4; cz++) {
                long key = WorldView.key(cx, cz);
                if (view.coversChunk(key)) {
                    keys.add(key);
                }
            }
        }
        Map<Long, Long> masks = main.call(() -> {
            World world = player.get().getWorld();
            Map<Long, Long> found = new HashMap<>();
            for (long key : keys) {
                long mask = Sections.mask(world, WorldView.chunkX(key), WorldView.chunkZ(key), palette);
                if (mask != 0) {
                    found.put(key, mask);
                }
            }
            return found;
        });
        List<Long> nearestFirst = new ArrayList<>(masks.keySet());
        nearestFirst.sort(Comparator.comparingLong(key -> closest(key, feet)));
        Gaze gaze = new Gaze(view, feet, eye(), wanted, accept, limit);
        for (long key : nearestFirst) {
            if (gaze.confirm(closest(key, feet))) {
                return gaze.seen;
            }
            gaze.collect(key, masks.get(key));
        }
        gaze.confirm(Long.MAX_VALUE);
        return gaze.seen;
    }

    /**
     * One block search. Chunks are read nearest first, and a found block is only
     * checked for a line of sight once no later chunk can hold a nearer one, so a
     * search stops as soon as it has enough without reading the whole view.
     */
    private final class Gaze {
        private record Found(Pos pos, long distance) {
        }

        private final WorldView view;
        private final Pos feet;
        private final Sight.Eye eye;
        private final Set<Material> wanted;
        private final Predicate<Candidate> accept;
        private final int limit;
        private final long reach = (long) radius * radius;
        private final PriorityQueue<Found> pending = new PriorityQueue<>(Comparator.comparingLong(Found::distance));
        private final List<Pos> seen = new ArrayList<>();
        private final boolean daylight;
        private int looks;

        Gaze(WorldView view, Pos feet, Sight.Eye eye, Set<Material> wanted, Predicate<Candidate> accept, int limit) {
            this.view = view;
            this.feet = feet;
            this.eye = eye;
            this.wanted = wanted;
            this.accept = accept;
            this.limit = limit;
            this.daylight = view.skyLight(feet.x(), feet.y() + 1, feet.z()) > 0;
        }

        /** Looks at every pending block nearer than {@code upTo}. True when the search is over. */
        boolean confirm(long upTo) {
            while (!pending.isEmpty() && pending.peek().distance() <= upTo) {
                Pos pos = pending.poll().pos();
                if (Sight.visible(view, eye, pos.x(), pos.y(), pos.z())) {
                    seen.add(pos);
                }
                if (seen.size() == limit || ++looks == MAX_LOOKS) {
                    return true;
                }
            }
            return false;
        }

        void collect(long key, long mask) {
            int baseX = WorldView.chunkX(key) << 4;
            int baseZ = WorldView.chunkZ(key) << 4;
            for (int section = 0; section < Long.SIZE; section++) {
                if ((mask & (1L << section)) == 0) {
                    continue;
                }
                int bottom = view.minY() + section * 16;
                for (int y = bottom; y < bottom + 16 && y < view.maxY(); y++) {
                    for (int x = baseX; x < baseX + 16; x++) {
                        for (int z = baseZ; z < baseZ + 16; z++) {
                            consider(x, y, z);
                        }
                    }
                }
            }
        }

        private void consider(int x, int y, int z) {
            if (!wanted.contains(view.type(x, y, z))) {
                return;
            }
            long dx = x - feet.x();
            long dy = y - feet.y();
            long dz = z - feet.z();
            long distance = dx * dx + dy * dy + dz * dz;
            if (distance > reach || !Sight.open(view, x, y, z) || daylight && distance > DARK_SIGHT && !lit(x, y, z)) {
                return;
            }
            Pos pos = new Pos(x, y, z);
            if (accept.test(new Candidate(view, pos))) {
                pending.add(new Found(pos, distance));
            }
        }

        /** Some open face gets light from the sky. */
        private boolean lit(int x, int y, int z) {
            for (int[] face : Sight.FACES) {
                if (view.skyLight(x + face[0], y + face[1], z + face[2]) > 0) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Squared distance from the feet to the nearest column of a chunk. */
    private static long closest(long key, Pos feet) {
        int minX = WorldView.chunkX(key) << 4;
        int minZ = WorldView.chunkZ(key) << 4;
        long dx = Math.max(0, Math.max(minX - feet.x(), feet.x() - (minX + 15)));
        long dz = Math.max(0, Math.max(minZ - feet.z(), feet.z() - (minZ + 15)));
        return dx * dx + dz * dz;
    }

    /** A block found by a search, with the copy it was found in. */
    public record Candidate(WorldView view, Pos pos) {
    }

    /** Living things of these kinds in sight that pass {@code wanted}, nearest first. Grown-ups only. */
    public List<UUID> creatures(Set<EntityType> kinds, Predicate<Entity> wanted, int limit) {
        return main.call(() -> {
            Player self = player.get();
            Location at = self.getLocation();
            return self.getWorld().getNearbyEntities(at, radius, radius, radius).stream()
                .filter(entity -> kinds.contains(entity.getType()) && entity instanceof LivingEntity && !entity.isDead())
                .filter(wanted)
                .filter(entity -> !(entity instanceof Ageable ageable) || ageable.isAdult())
                .filter(self::hasLineOfSight)
                .sorted(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(at)))
                .limit(limit)
                .map(Entity::getUniqueId)
                .toList();
        });
    }

    /**
     * Cliff faces in sight: one column at least {@link #CLIFF} blocks above its
     * neighbour, with bare rock showing. Hillsides are where ore shows without digging.
     */
    public List<Cliff> cliffs(int limit) {
        WorldView view = around();
        Pos feet = feet();
        Sight.Eye eye = eye();
        record Found(Cliff cliff, long distance) {
        }
        List<Found> found = new ArrayList<>();
        long reach = (long) radius * radius;
        int[][] around = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        for (int x = feet.x() - radius; x <= feet.x() + radius; x += 2) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z += 2) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                if (dx * dx + dz * dz > reach || !view.covers(x, z)) {
                    continue;
                }
                int low = view.highest(x, z);
                for (int[] side : around) {
                    int nx = x + side[0];
                    int nz = z + side[1];
                    if (!view.covers(nx, nz)) {
                        continue;
                    }
                    int high = view.highest(nx, nz);
                    if (high - low < CLIFF) {
                        continue;
                    }
                    int wallX = x + side[0] / 2;
                    int wallZ = z + side[1] / 2;
                    int wallY = low + 2;
                    Material floor = view.type(x, low, z);
                    if (!rock(view.type(wallX, wallY, wallZ)) || !Blocks.passable(view.type(x, low + 1, z))
                        || !Blocks.ground(floor) || Tag.LEAVES.isTagged(floor)) {
                        continue;
                    }
                    if (!Sight.visible(view, eye, wallX, wallY, wallZ)) {
                        continue;
                    }
                    found.add(new Found(new Cliff(new Pos(x, low + 1, z), new Pos(wallX, wallY, wallZ), high - low),
                        dx * dx + dz * dz));
                    break;
                }
            }
        }
        return found.stream().sorted(Comparator.comparingLong(Found::distance)).limit(limit)
            .map(Found::cliff).toList();
    }

    /**
     * Open ground under cover that the eyes can see into: the mouths and floors
     * of caves, deepest first. {@code below} is the highest y worth reporting.
     */
    public List<Pos> caves(int below, int limit) {
        Pos feet = feet();
        return floors(feet.y() - CAVE_DEPTH, below,
            pos -> (feet.y() - pos.y()) - 0.25 * Math.sqrt(pos.distanceSquared(feet)), limit);
    }

    /**
     * The bottoms of ravines, pits, and sinkholes in sight: open to the sky, at
     * {@code below} or lower, with a wall at least {@link #SHAFT_WALL} blocks
     * high beside them. Those are where big caves break the surface, so the
     * deepest come first. Water is not a way down.
     */
    public List<Pos> shafts(int below, int limit) {
        WorldView view = around();
        Pos feet = feet();
        List<Scored> found = new ArrayList<>();
        long reach = (long) radius * radius;
        for (int x = feet.x() - radius; x <= feet.x() + radius; x += 2) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z += 2) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                if (dx * dx + dz * dz > reach || !view.covers(x, z)) {
                    continue;
                }
                int top = view.highest(x, z);
                if (top + 1 > below || !Blocks.ground(view.type(x, top, z))
                    || !Blocks.passable(view.type(x, top + 1, z)) || Blocks.water(view.type(x, top + 1, z))) {
                    continue;
                }
                int wall = 0;
                for (int[] d : AROUND) {
                    int nx = x + d[0] * 3;
                    int nz = z + d[1] * 3;
                    if (view.covers(nx, nz)) {
                        wall = Math.max(wall, view.highest(nx, nz) - top);
                    }
                }
                if (wall >= SHAFT_WALL) {
                    Pos pos = new Pos(x, top + 1, z);
                    found.add(new Scored(pos, (feet.y() - pos.y()) - 0.1 * Math.sqrt(dx * dx + dz * dz)));
                }
            }
        }
        return inSight(view, found, limit);
    }

    /**
     * Open floor under cover that the eyes can see into, between {@code low} and
     * {@code high}, highest {@code worth} first. Every floor in a column counts,
     * so caves stacked over each other all show.
     */
    public List<Pos> floors(int low, int high, ToDoubleFunction<Pos> worth, int limit) {
        WorldView view = around();
        Pos feet = feet();
        List<Scored> found = new ArrayList<>();
        long reach = (long) radius * radius;
        int bottom = Math.max(view.minY() + 1, low);
        for (int x = feet.x() - radius; x <= feet.x() + radius; x += 2) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z += 2) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                if (dx * dx + dz * dz > reach || !view.covers(x, z)) {
                    continue;
                }
                for (int y : coveredFloors(view, x, z, bottom, high)) {
                    Pos pos = new Pos(x, y, z);
                    found.add(new Scored(pos, worth.applyAsDouble(pos)));
                }
            }
        }
        return inSight(view, found, limit);
    }

    /**
     * Cells in one column, top down, with room to stand, ground under them, and
     * rock or earth somewhere above. Never the top few blocks, which are only overhangs.
     */
    private static List<Integer> coveredFloors(WorldView view, int x, int z, int low, int high) {
        List<Integer> floors = new ArrayList<>();
        int highest = view.highest(x, z);
        int top = Math.min(highest - 3, high);
        boolean covered = false;
        for (int y = highest; y >= low; y--) {
            Material here = view.type(x, y, z);
            if (!Blocks.passable(here)) {
                covered |= !Tag.LEAVES.isTagged(here) && !Tag.LOGS.isTagged(here);
                continue;
            }
            if (covered && y <= top && !Blocks.water(here)
                && Blocks.passable(view.type(x, y + 1, z)) && Blocks.ground(view.type(x, y - 1, z))) {
                floors.add(y);
            }
        }
        return floors;
    }

    private record Scored(Pos pos, double score) {
    }

    /** The best scored spots the eyes can see into, best first. */
    private List<Pos> inSight(WorldView view, List<Scored> found, int limit) {
        Sight.Eye eye = eye();
        found.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Pos> seen = new ArrayList<>();
        int looks = 0;
        for (Scored candidate : found) {
            if (seen.size() == limit || looks++ == MAX_LOOKS) {
                break;
            }
            Pos pos = candidate.pos();
            if (Sight.seesInto(view, eye, pos.x(), pos.y(), pos.z())) {
                seen.add(pos);
            }
        }
        return seen;
    }

    /** The highest ground in sight, at least {@code minY} high, highest first. */
    public List<Pos> peaks(int minY, int limit) {
        WorldView view = around();
        Pos feet = feet();
        Sight.Eye eye = eye();
        List<Pos> found = new ArrayList<>();
        long reach = (long) radius * radius;
        for (int x = feet.x() - radius; x <= feet.x() + radius; x += 4) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z += 4) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                if (dx * dx + dz * dz > reach || !view.covers(x, z)) {
                    continue;
                }
                int top = view.highest(x, z);
                if (top >= minY && Blocks.ground(view.type(x, top, z)) && Blocks.passable(view.type(x, top + 1, z))) {
                    found.add(new Pos(x, top + 1, z));
                }
            }
        }
        found.sort(Comparator.comparingInt(Pos::y).reversed());
        List<Pos> seen = new ArrayList<>();
        for (Pos pos : found) {
            if (seen.size() == limit) {
                break;
            }
            if (Sight.seesInto(view, eye, pos.x(), pos.y(), pos.z())) {
                seen.add(pos);
            }
        }
        return seen;
    }

    /** The biomes on the ground in sight, each at its nearest sampled point. */
    public List<Patch> biomes() {
        Map<String, Patch> nearest = new LinkedHashMap<>();
        patches().forEach(patch -> nearest.putIfAbsent(patch.biome(), patch));
        return List.copyOf(nearest.values());
    }

    /** The ground in sight, sampled every few blocks, nearest first. */
    public List<Patch> patches() {
        WorldView view = around();
        Pos feet = feet();
        record Sample(Patch patch, long distance) {
        }
        List<Sample> samples = new ArrayList<>();
        long reach = (long) radius * radius;
        for (int x = feet.x() - radius; x <= feet.x() + radius; x += 8) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z += 8) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                long d = dx * dx + dz * dz;
                if (d > reach || !view.covers(x, z)) {
                    continue;
                }
                int top = view.highest(x, z);
                Biome biome = view.biome(x, top, z);
                if (biome != null) {
                    samples.add(new Sample(new Patch(biome.getKey().getKey(), new Pos(x, top + 1, z)), d));
                }
            }
        }
        return samples.stream().sorted(Comparator.comparingLong(Sample::distance)).map(Sample::patch).toList();
    }

    /** The biome under the helper's feet. */
    public String biomeHere() {
        return main.call(() -> {
            Location at = player.get().getLocation();
            return at.getWorld().getBiome(at).getKey().getKey();
        });
    }

    private Pos feet() {
        return main.call(() -> Pos.of(player.get().getLocation()));
    }

    private Sight.Eye eye() {
        return main.call(() -> Sight.Eye.of(player.get().getEyeLocation()));
    }

    private static final Set<Material> ROCK = rockTypes();

    private static Set<Material> rockTypes() {
        Set<Material> rock = EnumSet.noneOf(Material.class);
        rock.addAll(Tag.BASE_STONE_OVERWORLD.getValues());
        rock.addAll(Tag.TERRACOTTA.getValues());
        for (Material material : Material.values()) {
            if (material.isBlock() && material.name().endsWith("_ORE")) {
                rock.add(material);
            }
        }
        return rock;
    }

    /** Bare rock, the kind a cliff shows ore in. */
    public static boolean rock(Material material) {
        return ROCK.contains(material);
    }
}
