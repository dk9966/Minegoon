package dev.minegoon.jobs;

import dev.minegoon.knowledge.Knowledge.SearchOrders;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;

/** What a new task is for: so many of an item, for whom, where they stood, and how they said to search. */
public record Request(UUID owner, String ownerName, Location home, Material goal, int count, SearchOrders search) {

    /** A plain request, searched the way the helper knows best. */
    public static Request of(UUID owner, String ownerName, Location home, Material goal, int count) {
        return new Request(owner, ownerName, home, goal, count, SearchOrders.NONE);
    }
}
