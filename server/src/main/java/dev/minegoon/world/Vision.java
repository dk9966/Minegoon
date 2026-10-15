package dev.minegoon.world;

import dev.minegoon.MainThread;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
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

    /** Living things of these kinds in sight, nearest first. Grown-ups only. */
    public List<UUID> creatures(Set<EntityType> kinds, int limit) {
        return main.call(() -> {
            Player self = player.get();
            Location at = self.getLocation();
            return self.getWorld().getNearbyEntities(at, radius, radius, radius).stream()
                .filter(entity -> kinds.contains(entity.getType()) && entity instanceof LivingEntity && !entity.isDead())
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
        WorldView view = around();
        Pos feet = feet();
        Sight.Eye eye = eye();
        record Found(Pos pos, double score) {
        }
        List<Found> found = new ArrayList<>();
        long reach = (long) radius * radius;
        for (int x = feet.x() - radius; x <= feet.x() + radius; x += 2) {
            for (int z = feet.z() - radius; z <= feet.z() + radius; z += 2) {
                long dx = x - feet.x();
                long dz = z - feet.z();
                if (dx * dx + dz * dz > reach || !view.covers(x, z)) {
                    continue;
                }
                int top = Math.min(view.highest(x, z) - 3, below);
                int bottom = Math.max(view.minY() + 1, feet.y() - CAVE_DEPTH);
                boolean covered = false;
                for (int y = view.highest(x, z); y > bottom; y--) {
                    Material here = view.type(x, y, z);
                    if (!Blocks.passable(here)) {
                        covered |= !Tag.LEAVES.isTagged(here) && !Tag.LOGS.isTagged(here);
                        continue;
                    }
                    if (!covered || y > top || Blocks.water(here)) {
                        continue;
                    }
                    if (Blocks.passable(view.type(x, y + 1, z)) && Blocks.ground(view.type(x, y - 1, z))) {
                        double distance = Math.sqrt(dx * dx + dz * dz + (double) (y - feet.y()) * (y - feet.y()));
                        found.add(new Found(new Pos(x, y, z), (feet.y() - y) - 0.25 * distance));
                    }
                    break;
                }
            }
        }
        found.sort(Comparator.comparingDouble(Found::score).reversed());
        List<Pos> seen = new ArrayList<>();
        int looks = 0;
        for (Found candidate : found) {
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
        WorldView view = around();
        Pos feet = feet();
        Map<String, Pos> nearest = new HashMap<>();
        Map<String, Long> distance = new HashMap<>();
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
                if (biome == null) {
                    continue;
                }
                String name = biome.getKey().getKey();
                if (d < distance.getOrDefault(name, Long.MAX_VALUE)) {
                    distance.put(name, d);
                    nearest.put(name, new Pos(x, top + 1, z));
                }
            }
        }
        return nearest.entrySet().stream()
            .sorted(Comparator.comparingLong(entry -> distance.get(entry.getKey())))
            .map(entry -> new Patch(entry.getKey(), entry.getValue()))
            .toList();
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
