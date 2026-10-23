package dev.minegoon.agent;

import dev.minegoon.ItemSet;
import dev.minegoon.actions.Receipt;
import dev.minegoon.chat.Commands;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Inspector;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.MissionBoard;
import dev.minegoon.jobs.Request;
import dev.minegoon.jobs.Step;
import dev.minegoon.jobs.Task;
import dev.minegoon.knowledge.Knowledge.SearchOrders;
import dev.minegoon.knowledge.Knowledge.Way;
import dev.minegoon.planner.Planner;
import dev.minegoon.spawn.Helper;
import dev.minegoon.trips.Fighter;
import dev.minegoon.world.Vision;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.EntityType;

/**
 * The tools a mission's model works with. Each wraps something the helper can
 * already do, runs it to its end on the worker thread, and reports what the
 * game shows happened.
 */
public final class MissionTools {

    /** The moves a player can order. Visiting needs a structure, which orders do not name. */
    static final List<Way> ORDERABLE = Arrays.stream(Way.values()).filter(way -> way != Way.VISIT).toList();

    private static final String ITEM_AND_COUNT = """
        {"type": "object", "properties": {
          "item": {"type": "string", "description": "A Minecraft item id"},
          "count": {"type": "integer", "minimum": 1, "maximum": %d}
        }, "required": ["item", "count"]}""".formatted(Commands.MAX_COUNT);

    private static final String PLAYER = """
        {"type": "object", "properties": {
          "player": {"type": "string", "description": "Leave empty for the player who asked"}
        }}""";

    private final Foreman foreman;
    private final Foreman.Crew crew;
    private final MissionBoard missions;
    private final Inspector inspector;
    private final Vision vision;
    private final Senses senses;
    private final Helper helper;
    private final Predicate<String> knownBiome;

    public MissionTools(Foreman foreman, Foreman.Crew crew, MissionBoard missions, Inspector inspector, Vision vision,
                        Senses senses, Helper helper, Predicate<String> knownBiome) {
        this.foreman = foreman;
        this.crew = crew;
        this.missions = missions;
        this.inspector = inspector;
        this.vision = vision;
        this.senses = senses;
        this.helper = helper;
        this.knownBiome = knownBiome;
    }

    public List<Tool<Mission>> all() {
        return List.of(getItems(), bringBack(), give(), comeTo(), sleep(), fight(), planPreview(),
            senses.lookAroundTool(), senses.inventoryTool(), say());
    }

    private Tool<Mission> getItems() {
        String ways = ORDERABLE.stream().map(way -> "\"" + way.key() + "\"").collect(Collectors.joining(", "));
        return Tool.of("get_items", """
            Gets an item into the helper's hands: mines, crafts, smelts, farms, or hunts for it and everything it \
            needs, eating, sleeping, and fighting on the way, until it holds at least count, counting what it \
            already holds. Takes minutes. Ends done, or blocked with the reason. Does not hand anything over.""", """
            {"type": "object", "properties": {
              "item": {"type": "string", "description": "A Minecraft item id, such as oak_log or iron_ingot"},
              "count": {"type": "integer", "minimum": 1, "maximum": %d},
              "search": {"type": "object", "description": "Only when the player said how or where to look.",
                "properties": {
                  "ways": {"type": "array", "items": {"type": "string", "enum": [%s]},
                    "description": "Moves to try first. cliff: a bare rock face. peak: high ground. cave: down a cave opening. cavern: through a cave underground. roam: to the biomes. dig: strip mining, only when the player asked for strip or branch mining."},
                  "biomes": {"type": "array", "items": {"type": "string"}, "description": "Biome ids, such as plains"}
                }}
            }, "required": ["item", "count"]}""".formatted(Commands.MAX_COUNT, ways), this::getItems);
    }

    private Receipt getItems(Args args, Mission mission) {
        int count = args.count("count");
        SearchOrders orders = orders(args.object("search"));
        Material item = args.item("item");
        Long carrying = mission.pending().map(Mission.Line::task).orElse(null);
        long taskId = carrying != null ? carrying : foreman.post(new Request(mission.owner(), mission.ownerName(),
            mission.home(), item, count, orders, mission.id()));
        if (carrying == null) {
            missions.fetching(mission.id(), taskId);
        }
        Task task = foreman.fetch(taskId);
        String status = task.status().name().toLowerCase(Locale.ROOT);
        String observed = (task.reason() == null ? status : status + ": " + task.reason()) + ". Holding "
            + inspector.have(ItemSet.of(item)) + " " + ItemSet.key(item) + "."
            + (task.deaths() == 0 ? "" : " Died " + task.deaths() + " times on the way.");
        return new Receipt("get_items", task.status() == Task.Status.DONE, observed);
    }

    private SearchOrders orders(Args search) {
        List<Way> ways = new ArrayList<>();
        for (String name : search.texts("ways")) {
            ways.add(Way.parse(name).filter(ORDERABLE::contains)
                .orElseThrow(() -> new Args.Refused("there is no search move called " + name)));
        }
        List<String> biomes = search.texts("biomes");
        for (String biome : biomes) {
            if (!knownBiome.test(biome)) {
                throw new Args.Refused("there is no biome called " + biome);
            }
        }
        return new SearchOrders(ways, biomes);
    }

    private Tool<Mission> bringBack() {
        return Tool.of("bring_back", """
            Walks back and hands items to the player who asked, or drops them where they asked if they \
            are not around.""", ITEM_AND_COUNT, (args, mission) -> {
                int count = args.count("count");
                Material item = args.item("item");
                int handed = crew.homecoming().deliver(mission.owner(), mission.home(), ItemSet.of(item), count);
                return handed(handed, item, mission.ownerName());
            });
    }

    private Tool<Mission> give() {
        return Tool.of("give", "Walks to a player and hands them items.", """
            {"type": "object", "properties": {
              "player": {"type": "string", "description": "Leave empty for the player who asked"},
              "item": {"type": "string", "description": "A Minecraft item id"},
              "count": {"type": "integer", "minimum": 1, "maximum": %d}
            }, "required": ["item", "count"]}""".formatted(Commands.MAX_COUNT), (args, mission) -> {
                int count = args.count("count");
                Material item = args.item("item");
                UUID player = player(args, mission);
                int handed = crew.homecoming().deliver(player, null, ItemSet.of(item), count);
                String name = args.text("player", "");
                return handed(handed, item, name.isEmpty() ? mission.ownerName() : name);
            });
    }

    private Tool<Mission> comeTo() {
        return Tool.of("come_to", """
            Walks to a player, following them if they move. With no player, walks to the player who asked, or to \
            where they asked if they are not around.""", PLAYER, (args, mission) -> {
                String name = args.text("player", "");
                if (name.isEmpty()) {
                    crew.homecoming().goBack(mission.owner(), mission.home());
                } else {
                    crew.homecoming().comeTo(player(args, mission));
                }
                return Receipt.confirmed("come_to", "Reached " + (name.isEmpty() ? mission.ownerName() : name) + ".");
            });
    }

    private Tool<Mission> sleep() {
        return Tool.of("sleep", """
            Places a bed the helper holds and sleeps until morning. Only at night, and only with a bed; \
            get_items white_bed first if it has none.""", (args, mission) -> {
                if (!crew.sleeper().night()) {
                    return Receipt.refused("sleep", "It is day; beds only work at night.");
                }
                crew.sleeper().sleep();
                return Receipt.confirmed("sleep", "Slept until morning.");
            });
    }

    private Tool<Mission> fight() {
        return Tool.of("fight", "Fights the nearest creature of a kind in sight until one of them is dead.", """
            {"type": "object", "properties": {
              "creature": {"type": "string", "description": "An entity id, such as zombie or cow"}
            }, "required": ["creature"]}""", (args, mission) -> {
                String name = args.text("creature");
                NamespacedKey key = NamespacedKey.fromString(name.toLowerCase(Locale.ROOT));
                EntityType kind = key == null ? null
                    : RegistryAccess.registryAccess().getRegistry(RegistryKey.ENTITY_TYPE).get(key);
                if (kind == null || !kind.isAlive()) {
                    throw new Args.Refused("there is no creature called " + name);
                }
                List<UUID> seen = vision.creatures(Set.of(kind), creature -> true, 1);
                if (seen.isEmpty()) {
                    return Receipt.refused("fight", "No " + name + " in sight.");
                }
                Fighter.Tally tally = crew.fighter().fight(seen.getFirst());
                if (tally.won()) {
                    return Receipt.confirmed("fight", "Killed the " + name + ".");
                }
                return Receipt.refused("fight", tally.died() ? "Died fighting the " + name + "."
                    : tally.fled() ? "Backed off from the " + name + " to stay alive." : "The " + name + " got away.");
            });
    }

    private Tool<Mission> planPreview() {
        return Tool.of("plan_preview", """
            Shows every step get_items would take for an item from what the helper holds now, and for each \
            thing it has to find, where that is and how it is looked for. Does none of them.""", ITEM_AND_COUNT,
            (args, mission) -> {
                int count = args.count("count");
                Material item = args.item("item");
                try {
                    List<Step> steps = foreman.preview(item, count);
                    return Receipt.confirmed("plan_preview", IntStream.range(0, steps.size())
                        .mapToObj(i -> {
                            String where = crew.prospector().where(steps.get(i));
                            return (i + 1) + ". " + steps.get(i).describe() + (where == null ? "" : " [" + where + "]");
                        })
                        .collect(Collectors.joining("; ")) + ".");
                } catch (Planner.Unplannable none) {
                    return Receipt.refused("plan_preview", "No plan: " + none.getMessage() + ".");
                }
            });
    }

    private Tool<Mission> say() {
        return Tool.of("say", "Says something in chat. Keep it short; the helper reports most things itself.", """
            {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}""",
            (args, mission) -> {
                helper.say(args.text("text"));
                return Receipt.confirmed("say", "Said it.");
            });
    }

    private UUID player(Args args, Mission mission) {
        String name = args.text("player", "");
        if (!name.isEmpty()) {
            return senses.player(name).orElseThrow(() -> new Args.Refused("no player called " + name
                + " is in this world"));
        }
        if (mission.owner() == null) {
            throw new Args.Refused("the request came from the console, so name a player");
        }
        return mission.owner();
    }

    private static Receipt handed(int handed, Material item, String to) {
        return handed == 0
            ? Receipt.refused("hand over", "Had no " + ItemSet.key(item) + " to hand over.")
            : Receipt.confirmed("hand over", "Handed " + handed + " " + ItemSet.key(item) + " to " + to + ".");
    }
}
