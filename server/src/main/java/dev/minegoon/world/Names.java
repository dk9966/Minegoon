package dev.minegoon.world;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

/** Whether a plain name such as plains, iron_ore, or cow is something the game has. */
public final class Names {

    private Names() {
    }

    public static boolean biome(String name) {
        NamespacedKey key = NamespacedKey.fromString(name);
        return key != null && RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME).get(key) != null;
    }

    /** An item, a block, or a creature. */
    public static boolean thing(String name) {
        if (Material.matchMaterial(name) != null) {
            return true;
        }
        NamespacedKey key = NamespacedKey.fromString(name);
        return key != null && RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(key) != null;
    }
}
