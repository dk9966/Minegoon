package dev.minegoon.safety;

import dev.minegoon.spawn.Helper;
import dev.minegoon.world.BlockReader;
import java.util.logging.Logger;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * The last line of defence: if the helper ever tries to break a block the
 * rules forbid, the break is cancelled here, whatever code path asked for it.
 */
public final class BreakGuard implements Listener {

    private final Helper helper;
    private final BreakPolicy policy;
    private final Runnable onCatch;
    private final Logger log;

    public BreakGuard(Helper helper, BreakPolicy policy, Runnable onCatch, Logger log) {
        this.helper = helper;
        this.policy = policy;
        this.onCatch = onCatch;
        this.log = log;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (helper.is(event.getPlayer())
            && !policy.mayBreak(BlockReader.live(block.getWorld()), block.getX(), block.getY(), block.getZ())) {
            event.setCancelled(true);
            onCatch.run();
            log.warning("Guard stopped the helper breaking " + block.getType() + " at " + block.getLocation());
        }
    }
}
