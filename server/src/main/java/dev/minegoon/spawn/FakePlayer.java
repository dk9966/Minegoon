package dev.minegoon.spawn;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.NameAndId;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Creates and drives a server-side player that has no client and no Microsoft
 * account. This is the only class that touches server internals, so a Minecraft
 * update should only ever break this file.
 */
public final class FakePlayer {

    private FakePlayer() {
    }

    /** Places the player in the world, restoring its saved inventory and position if it has played before. */
    @SuppressWarnings("deprecation")
    public static Player spawn(String name, Location fallback) {
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel level = ((CraftWorld) fallback.getWorld()).getHandle();
        GameProfile profile = new GameProfile(offlineUuid(name), name);
        ServerPlayer player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());

        try (var reporter = new ProblemReporter.ScopedCollector(player.problemPath(), LogUtils.getLogger())) {
            var saved = server.getPlayerList()
                .loadPlayerData(new NameAndId(profile))
                .map(tag -> TagValueInput.create(reporter, server.registryAccess(), tag));
            saved.ifPresent(player::load);
            var position = saved
                .flatMap(input -> input.read(ServerPlayer.SavedPosition.MAP_CODEC))
                .orElse(ServerPlayer.SavedPosition.EMPTY);
            player.snapTo(
                position.position().orElse(new Vec3(fallback.getX(), fallback.getY(), fallback.getZ())),
                position.rotation().map(rotation -> rotation.x).orElse(fallback.getYaw()),
                position.rotation().map(rotation -> rotation.y).orElse(fallback.getPitch()));
        }

        server.getPlayerList().placeNewPlayer(new NoClient(), player, CommonListenerCookie.createInitial(profile, false));
        return player.getBukkitEntity();
    }

    /** Saves and removes the player, the same way a real player logs out. */
    public static void despawn(Player player) {
        MinecraftServer.getServer().getPlayerList().remove(handle(player));
    }

    /**
     * What a client's keys would say this tick. Forward and strafe run from -1 to 1.
     * Sneaking holds the body still on a ladder or vine and keeps it from walking off edges.
     */
    public record Controls(float forward, float strafe, boolean jump, boolean sprint, boolean sneak) {
        public static final Controls STILL = new Controls(0, 0, false, false);

        public Controls(float forward, float strafe, boolean jump, boolean sprint) {
            this(forward, strafe, jump, sprint, false);
        }
    }

    /**
     * Runs one tick of survival rules: hunger, regeneration, air, fire, item
     * pickup and finishing a bite of food. A real client drives this tick through
     * its connection; ours has none. Chunk loading follows the player only when
     * told it moved, which the move-packet handler would normally do.
     *
     * The game's own movement runs on the controls: gravity, jumps, sprinting,
     * swimming, ladders and knockback all happen, and so does fall damage, which
     * the server would otherwise take from the client's reports.
     */
    public static void tick(Player player, Controls controls) {
        ServerPlayer handle = handle(player);
        if (!handle.isAlive()) {
            return;
        }
        double x = handle.getX();
        double y = handle.getY();
        double z = handle.getZ();
        handle.connection.resetPosition();
        handle.xo = x;
        handle.yo = y;
        handle.zo = z;
        handle.zza = controls.forward();
        handle.xxa = controls.strafe();
        handle.setJumping(controls.jump());
        handle.setShiftKeyDown(controls.sneak());
        handle.setSprinting(controls.sprint() && !controls.sneak() && handle.getFoodData().getFoodLevel() > 6);
        handle.doTick();
        handle.doCheckFallDamage(handle.getX() - x, handle.getY() - y, handle.getZ() - z, handle.onGround());
        handle.level().getChunkSource().move(handle);
    }

    /**
     * Turns the body and head without a teleport, so movement in progress keeps
     * its speed. The eyes never settle on a calm enderman's head.
     */
    public static void face(Player player, float yaw, float pitch) {
        ServerPlayer handle = handle(player);
        handle.setYRot(yaw);
        handle.setXRot(Gaze.pitch(player, yaw, pitch));
        handle.setYHeadRot(yaw);
        handle.setYBodyRot(yaw);
    }

    /** Lets go of a drawn bow, the way releasing the use key does. */
    public static void release(Player player) {
        handle(player).releaseUsingItem();
    }

    /** Walked into a wall or a ledge on the last tick. */
    public static boolean bumped(Player player) {
        return handle(player).horizontalCollision;
    }

    /** Standing on something, as the server's own movement worked it out. */
    public static boolean grounded(Player player) {
        return handle(player).onGround();
    }

    /**
     * The block holding the body up, which may be beside the feet when it stands
     * on an edge. That is the block whose touch it feels, like a magma block's burn.
     */
    public static Block under(Player player) {
        BlockPos on = handle(player).getOnPos();
        return player.getWorld().getBlockAt(on.getX(), on.getY(), on.getZ());
    }

    /** On a ladder, vine or scaffolding. */
    public static boolean climbing(Player player) {
        return handle(player).onClimbable();
    }

    /** True while falling with the momentum of a jump: the moment a hit lands as a critical one. */
    public static boolean falling(Player player) {
        ServerPlayer handle = handle(player);
        return handle.fallDistance > 0 && !handle.onGround() && handle.getDeltaMovement().y < 0;
    }

    /**
     * Uses the main-hand item on the top face of a block, the way a right-click does.
     * Callers turn the helper to face the block first when the item cares about direction.
     */
    public static boolean useOn(Player player, Block block) {
        return useOn(player, block, BlockFace.UP);
    }

    /** Uses the main-hand item on one face of a block: a block placed this way goes on that side. */
    public static boolean useOn(Player player, Block block, BlockFace face) {
        ServerPlayer handle = handle(player);
        BlockPos pos = new BlockPos(block.getX(), block.getY(), block.getZ());
        Vec3 hit = new Vec3(block.getX() + 0.5 + face.getModX() * 0.5, block.getY() + 0.5 + face.getModY() * 0.5,
            block.getZ() + 0.5 + face.getModZ() * 0.5);
        Direction direction = Direction.valueOf(face.name());
        BlockHitResult at = new BlockHitResult(hit, direction, pos, false);
        var stack = handle.getItemInHand(InteractionHand.MAIN_HAND);
        return handle.gameMode.useItemOn(handle, handle.level(), stack, InteractionHand.MAIN_HAND, at).consumesAction();
    }

    /**
     * Uses the main-hand item at whatever the helper is looking at, the way a
     * right-click into the air does. Buckets work like this: they pick the fluid
     * along the line of sight, not a clicked block.
     */
    public static boolean use(Player player) {
        ServerPlayer handle = handle(player);
        var stack = handle.getItemInHand(InteractionHand.MAIN_HAND);
        return handle.gameMode.useItem(handle, handle.level(), stack, InteractionHand.MAIN_HAND).consumesAction();
    }

    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    /** A connection with an open channel that silently drops everything sent to it. */
    private static final class NoClient extends Connection {

        NoClient() {
            super(PacketFlow.SERVERBOUND);
            this.channel = new EmbeddedChannel();
        }

        @Override
        public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocol, T listener) {
        }

        @Override
        public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        }

        @Override
        public void setReadOnly() {
        }
    }
}
