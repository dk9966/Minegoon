package dev.minegoon.spawn;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
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
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import org.bukkit.Location;
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
     * Runs one tick of survival rules: hunger, regeneration, air, fire, item
     * pickup and finishing a bite of food. A real client drives this tick through
     * its connection; ours has none. Position is restored afterwards because the
     * server never simulates a player's movement. Chunk loading follows the player
     * only when told it moved, which the move-packet handler would normally do.
     */
    public static void tick(Player player) {
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
        handle.doTick();
        handle.absSnapTo(x, y, z, handle.getYRot(), handle.getXRot());
        handle.level().getChunkSource().move(handle);
    }

    /** The client normally reports this; mining is five times slower while it is false. */
    public static void setOnGround(Player player, boolean onGround) {
        handle(player).setOnGround(onGround);
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
