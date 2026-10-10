package dev.minegoon.actions;

import dev.minegoon.ItemSet;
import dev.minegoon.world.Pos;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;

/**
 * Crafting and smelting. Recipes come straight from the server. Crafting
 * tables and furnaces are placed beside the helper when needed and picked
 * back up afterwards.
 */
public final class Crafter {

    private static final int STATION_REACH = 4;
    private static final int TICKS_PER_ITEM = 200;
    private static final List<Material> PREFERRED_FUEL = List.of(Material.COAL, Material.CHARCOAL);

    private final Body body;
    private final Miner miner;
    private final Placer placer;
    private final Pickup pickup;

    public Crafter(Body body, Miner miner, Placer placer, Pickup pickup) {
        this.body = body;
        this.miner = miner;
        this.placer = placer;
        this.pickup = pickup;
    }

    public Receipt craft(List<Recipe> candidates, ItemSet output, int times) {
        String action = "craft " + output.name() + " x" + times;
        int before = body.main().call(() -> Hands.count(body.player(), output));
        Station table = null;
        for (int made = 0; made < times; made++) {
            Recipe recipe = body.main().call(() -> candidates.stream()
                .filter(r -> plan(body.player().getInventory(), r) != null).findFirst().orElse(null));
            if (recipe == null) {
                packUp(table);
                return Receipt.refused(action, "missing ingredients after " + made + " of " + times);
            }
            if (CraftingGrid.needsTable(recipe) && table == null) {
                table = station(Material.CRAFTING_TABLE, false);
            }
            body.main().run(() -> craftOnce(body.player(), recipe));
        }
        packUp(table);
        int gained = body.main().call(() -> Hands.count(body.player(), output)) - before;
        return gained > 0
            ? Receipt.confirmed(action, "gained " + gained + " " + output.name())
            : Receipt.refused(action, "nothing was made");
    }

    public Receipt smelt(ItemSet input, ItemSet output, int count) {
        String action = "smelt " + count + " " + output.name();
        Station furnace = station(Material.FURNACE, true);
        int produced = 0;
        while (produced < count) {
            int wanted = count - produced;
            int loaded = body.main().call(() -> load(furnace.pos(), input, wanted));
            if (loaded == 0) {
                packUp(furnace);
                return Receipt.refused(action, "has no " + input.name() + " left to smelt");
            }
            String stall = waitForFurnace(furnace.pos(), loaded);
            produced += body.main().call(() -> unload(furnace.pos()));
            if (stall != null) {
                packUp(furnace);
                return Receipt.refused(action, stall + " after " + produced);
            }
        }
        packUp(furnace);
        return Receipt.confirmed(action, "took " + produced + " " + output.name() + " from the furnace");
    }

    private String waitForFurnace(Pos pos, int loaded) {
        int waited = 0;
        while (true) {
            body.main().sleep(20);
            waited += 20;
            String problem = body.main().call(() -> {
                Furnace furnace = furnace(pos);
                FurnaceInventory slots = furnace.getInventory();
                if (empty(slots.getSmelting())) {
                    return "";
                }
                if (furnace.getBurnTime() <= 0 && empty(slots.getFuel()) && !addFuel(slots, 1)) {
                    return "ran out of fuel";
                }
                return null;
            });
            if (problem != null) {
                return problem.isEmpty() ? null : problem;
            }
            if (waited > loaded * TICKS_PER_ITEM + 400) {
                return "the furnace stalled";
            }
        }
    }

    private int load(Pos pos, ItemSet input, int wanted) {
        Player player = body.player();
        Material material = Hands.mostOf(player, input);
        if (material == null) {
            return 0;
        }
        int amount = Math.min(Math.min(wanted, material.getMaxStackSize()), Hands.count(player, ItemSet.of(material)));
        FurnaceInventory slots = furnace(pos).getInventory();
        if (!empty(slots.getSmelting())) {
            return 0;
        }
        Hands.remove(player, ItemSet.of(material), amount);
        slots.setSmelting(new ItemStack(material, amount));
        addFuel(slots, amount);
        return amount;
    }

    /** Puts enough fuel in for {@code items} more smelts, preferring coal over wood. */
    private boolean addFuel(FurnaceInventory slots, int items) {
        Player player = body.player();
        ItemStack current = slots.getFuel();
        for (Material fuel : fuelChoices(player)) {
            if (!empty(current) && current.getType() != fuel) {
                continue;
            }
            int burn = fuel.asItemType().getBurnDuration();
            int needed = (int) Math.ceil((double) items * TICKS_PER_ITEM / burn);
            int room = fuel.getMaxStackSize() - (empty(current) ? 0 : current.getAmount());
            int moved = Hands.remove(player, ItemSet.of(fuel), Math.min(needed, room));
            if (moved > 0) {
                slots.setFuel(new ItemStack(fuel, moved + (empty(current) ? 0 : current.getAmount())));
                return true;
            }
        }
        return false;
    }

    private static List<Material> fuelChoices(Player player) {
        Stream<Material> wood = Stream.of(Material.values())
            .filter(m -> Tag.PLANKS.isTagged(m) || Tag.LOGS_THAT_BURN.isTagged(m) || m == Material.STICK);
        return Stream.concat(PREFERRED_FUEL.stream(), wood)
            .filter(m -> Hands.count(player, ItemSet.of(m)) > 0)
            .toList();
    }

    private int unload(Pos pos) {
        Player player = body.player();
        FurnaceInventory slots = furnace(pos).getInventory();
        int taken = empty(slots.getResult()) ? 0 : slots.getResult().getAmount();
        for (ItemStack stack : new ItemStack[] {slots.getResult(), slots.getSmelting(), slots.getFuel()}) {
            if (!empty(stack)) {
                give(player, stack.clone());
            }
        }
        slots.clear();
        return taken;
    }

    private Furnace furnace(Pos pos) {
        Block block = pos.block(body.world());
        if (!(block.getState() instanceof Furnace furnace)) {
            throw new ActionFailed("the furnace at " + pos + " is gone");
        }
        return furnace;
    }

    private record Station(Pos pos, boolean placedHere) {
    }

    /** Uses a station within reach, or places one. Furnaces must be the helper's own: their contents are not ours. */
    private Station station(Material type, boolean ownOnly) {
        Pos existing = body.main().call(() -> {
            Player player = body.player();
            Block center = player.getLocation().getBlock();
            for (int dx = -STATION_REACH; dx <= STATION_REACH; dx++) {
                for (int dy = -1; dy <= 2; dy++) {
                    for (int dz = -STATION_REACH; dz <= STATION_REACH; dz++) {
                        Block block = center.getRelative(dx, dy, dz);
                        if (block.getType() == type && (!ownOnly || body.policy().placedByHelper(block))) {
                            return Pos.of(block);
                        }
                    }
                }
            }
            return null;
        });
        return existing != null ? new Station(existing, false) : new Station(placer.placeBeside(type), true);
    }

    private void packUp(Station station) {
        if (station != null && station.placedHere() && miner.mine(station.pos()).confirmed()) {
            pickup.collect(station.pos(), 3, Checkpoint.NONE);
        }
    }

    private static void craftOnce(Player player, Recipe recipe) {
        PlayerInventory inventory = player.getInventory();
        int[] use = plan(inventory, recipe);
        if (use == null) {
            throw new ActionFailed("ingredients disappeared mid-craft");
        }
        for (int slot = 0; slot < use.length; slot++) {
            if (use[slot] > 0) {
                ItemStack stack = inventory.getItem(slot);
                stack.setAmount(stack.getAmount() - use[slot]);
                inventory.setItem(slot, stack.getAmount() == 0 ? null : stack);
            }
        }
        give(player, recipe.getResult().clone());
        player.swingMainHand();
    }

    /** How many items to take from each slot to fill the recipe's cells, or null if it cannot be filled. */
    private static int[] plan(PlayerInventory inventory, Recipe recipe) {
        List<RecipeChoice> cells = CraftingGrid.cells(recipe);
        ItemStack[] storage = inventory.getStorageContents();
        int[] use = new int[storage.length];
        if (cells.isEmpty()) {
            return null;
        }
        for (RecipeChoice cell : cells) {
            int slot = -1;
            for (int i = 0; i < storage.length && slot < 0; i++) {
                if (!empty(storage[i]) && storage[i].getAmount() > use[i] && cell.test(storage[i])) {
                    slot = i;
                }
            }
            if (slot < 0) {
                return null;
            }
            use[slot]++;
        }
        return use;
    }

    private static void give(Player player, ItemStack stack) {
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(stack);
        overflow.values().forEach(rest -> player.getWorld().dropItem(player.getLocation(), rest));
    }

    private static boolean empty(ItemStack stack) {
        return stack == null || stack.getType().isAir() || stack.getAmount() <= 0;
    }
}
