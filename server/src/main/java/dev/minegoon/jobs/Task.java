package dev.minegoon.jobs;

import dev.minegoon.ItemSet;
import dev.minegoon.knowledge.Knowledge.SearchOrders;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;

/**
 * One request, such as "get 5 diamond", where to bring the result, how the
 * player said to search, and the mission it is a step of, if any.
 */
public record Task(long id, UUID owner, String ownerName, Material goal, int count, SearchOrders search,
                   Long missionId, Status status, String reason, String world, double homeX, double homeY,
                   double homeZ, Set<String> avoid, int deaths, int modelCalls, long createdAt) {

    public enum Status {
        ACTIVE, PAUSED, DONE, BLOCKED, CANCELLED;

        public boolean finished() {
            return this == DONE || this == BLOCKED || this == CANCELLED;
        }
    }

    public ItemSet goalItems() {
        return ItemSet.of(goal);
    }

    public Location home() {
        return new Location(Bukkit.getWorld(world), homeX, homeY, homeZ);
    }

    public String describe() {
        return count + " " + ItemSet.key(goal);
    }
}
