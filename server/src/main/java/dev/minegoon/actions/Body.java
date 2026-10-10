package dev.minegoon.actions;

import dev.minegoon.MainThread;
import dev.minegoon.safety.BreakPolicy;
import dev.minegoon.spawn.Helper;
import dev.minegoon.world.BlockReader;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** What every action needs: the helper, the server thread, and the break rules. */
public record Body(MainThread main, Helper helper, BreakPolicy policy) {

    /** Main thread. */
    public Player player() {
        Player player = helper.player();
        if (player == null || player.isDead()) {
            throw new ActionFailed("the helper is not standing in the world");
        }
        return player;
    }

    /** Main thread. */
    public World world() {
        return player().getWorld();
    }

    /** Main thread. */
    public BlockReader live() {
        return BlockReader.live(world());
    }
}
