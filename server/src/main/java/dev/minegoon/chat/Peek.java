package dev.minegoon.chat;

import dev.minegoon.ItemSet;
import dev.minegoon.spawn.Helper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * A live, look-only view of the helper's inventory. The window is a copy, so
 * clicks never move what the helper is actually holding. While it stays open
 * it keeps up with pickups, crafting, and tool wear.
 */
public final class Peek implements Listener {

    private static final int SIZE = 45;
    private static final String[] EMPTY_GEAR = {
        "No helmet", "No chestplate", "No leggings", "No boots", "No off hand"
    };

    private final Plugin plugin;
    private final Helper helper;
    private final ItemStack[] emptyGear = new ItemStack[EMPTY_GEAR.length];
    private final Map<UUID, Inventory> open = new HashMap<>();

    public Peek(Plugin plugin, Helper helper) {
        this.plugin = plugin;
        this.helper = helper;
        for (int i = 0; i < EMPTY_GEAR.length; i++) {
            emptyGear[i] = label(EMPTY_GEAR[i]);
        }
    }

    /** Opens the window for a player who is in the world. */
    public void open(UUID id) {
        onMain(() -> {
            Player player = id == null ? null : Bukkit.getPlayer(id);
            if (player == null || helper.is(player)) {
                helper.say("Looking at my inventory only works for a player in the world.");
                return;
            }
            if (!helper.online() || helper.player().isDead()) {
                tell(player, "I'm not standing in the world right now.");
                return;
            }
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Mirror) {
                tell(player, "You already have my inventory open.");
                return;
            }
            Mirror mirror = new Mirror(Component.text(helper.name() + "'s inventory"));
            apply(mirror.getInventory(), slots());
            player.openInventory(mirror.getInventory());
            open.put(player.getUniqueId(), mirror.getInventory());
            tell(player, "Have a look. Top row is armor and off hand, bottom row is my hotbar. Clicking won't take anything.");
        });
    }

    /** Refreshes every open window from the helper's inventory. Main thread only. */
    public void tick() {
        if (open.isEmpty() || !helper.online()) {
            return;
        }
        ItemStack[] slots = slots();
        for (Inventory inventory : open.values()) {
            apply(inventory, slots);
        }
    }

    public void closeAll() {
        for (UUID id : List.copyOf(open.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.closeInventory();
            }
        }
        open.clear();
    }

    /** The same contents as text, for the console. Main thread only. */
    public List<String> report() {
        if (!helper.online()) {
            return List.of(helper.name() + " is not in the world.");
        }
        PlayerInventory inventory = helper.player().getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        return List.of(
            "Helmet: " + describe(inventory.getHelmet()),
            "Chestplate: " + describe(inventory.getChestplate()),
            "Leggings: " + describe(inventory.getLeggings()),
            "Boots: " + describe(inventory.getBoots()),
            "Off hand: " + describe(inventory.getItemInOffHand()),
            "Hotbar: " + contents(storage, 0, Math.min(9, storage.length), inventory.getHeldItemSlot()),
            "Pack: " + contents(storage, 9, storage.length, -1));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Mirror) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Mirror) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Mirror) {
            open.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        open.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Five rows: armor and off hand, then the pack in the order the helper sees
     * it, then the hotbar. The held hotbar slot is marked.
     */
    private ItemStack[] slots() {
        ItemStack[] slots = new ItemStack[SIZE];
        PlayerInventory inventory = helper.player().getInventory();
        ItemStack[] worn = {
            inventory.getHelmet(),
            inventory.getChestplate(),
            inventory.getLeggings(),
            inventory.getBoots(),
            inventory.getItemInOffHand()
        };
        for (int i = 0; i < worn.length; i++) {
            slots[i] = show(worn[i], emptyGear[i], false);
        }
        ItemStack[] storage = inventory.getStorageContents();
        int held = inventory.getHeldItemSlot();
        for (int i = 9; i < storage.length && i < 36; i++) {
            slots[i] = show(storage[i], null, false);
        }
        for (int i = 0; i < 9 && i < storage.length; i++) {
            slots[36 + i] = show(storage[i], null, i == held);
        }
        return slots;
    }

    private static ItemStack show(ItemStack stack, ItemStack empty, boolean inHand) {
        if (stack == null || stack.isEmpty()) {
            return empty == null ? null : empty.clone();
        }
        ItemStack shown = stack.clone();
        if (inHand) {
            shown.editMeta(meta -> {
                List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
                lore.add(Component.text("In hand", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
                meta.lore(lore);
            });
        }
        return shown;
    }

    private static void apply(Inventory inventory, ItemStack[] slots) {
        for (int i = 0; i < slots.length; i++) {
            if (changed(inventory.getItem(i), slots[i])) {
                inventory.setItem(i, slots[i] == null ? null : slots[i].clone());
            }
        }
    }

    /** Amount and meta, so an unchanged slot is not sent to the client again. */
    private static boolean changed(ItemStack current, ItemStack next) {
        boolean currentEmpty = current == null || current.isEmpty();
        boolean nextEmpty = next == null || next.isEmpty();
        if (currentEmpty || nextEmpty) {
            return currentEmpty != nextEmpty;
        }
        return current.getAmount() != next.getAmount() || !current.isSimilar(next);
    }

    private static String contents(ItemStack[] storage, int from, int until, int held) {
        List<String> parts = new ArrayList<>();
        for (int slot = from; slot < until; slot++) {
            ItemStack stack = storage[slot];
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            parts.add(describe(stack) + (slot == held ? " (in hand)" : ""));
        }
        return parts.isEmpty() ? "empty" : String.join(", ", parts);
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "none";
        }
        String text = stack.getAmount() + " " + ItemSet.key(stack.getType());
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof Damageable damage && damage.getDamage() > 0 && stack.getType().getMaxDurability() > 0) {
            int max = stack.getType().getMaxDurability();
            text += " (" + (max - damage.getDamage()) + "/" + max + " left)";
        }
        String enchants = enchants(meta);
        if (!enchants.isEmpty()) {
            text += " [" + enchants + "]";
        }
        return text;
    }

    private static String enchants(ItemMeta meta) {
        if (meta == null) {
            return "";
        }
        Map<Enchantment, Integer> enchants = new HashMap<>(meta.getEnchants());
        if (meta instanceof EnchantmentStorageMeta stored) {
            enchants.putAll(stored.getStoredEnchants());
        }
        return enchants.entrySet().stream()
            .map(entry -> entry.getKey().getKey().getKey() + " " + entry.getValue())
            .sorted()
            .collect(Collectors.joining(", "));
    }

    private static ItemStack label(String name) {
        ItemStack pane = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        pane.editMeta(meta -> meta.displayName(
            Component.text(name, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        return pane;
    }

    private void tell(Player player, String text) {
        player.sendMessage(Component.text("<" + helper.name() + "> " + text));
    }

    private void onMain(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /** Marks inventories this class opened, so clicks in them can be refused. */
    private static final class Mirror implements InventoryHolder {

        private final Inventory inventory;

        Mirror(Component title) {
            inventory = Bukkit.createInventory(this, SIZE, title);
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
