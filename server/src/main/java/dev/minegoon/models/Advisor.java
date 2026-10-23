package dev.minegoon.models;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minegoon.knowledge.Knowledge.Way;
import dev.minegoon.knowledge.Lessons.Lesson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Material;

/**
 * Asked only when what the helper knows has run out: a search came up dry
 * twice, or the same step keeps failing. The answer is a lesson in the same
 * words as knowledge.yml, checked against the moves, biomes, and structures that
 * exist, so a model can add to the knowledge but not invent things.
 */
public final class Advisor {

    private static final String SYSTEM = """
        You help a Minecraft Java 26.3 survival bot find something it could not find.
        It sees every block and animal in render distance that nothing hides, and tries
        moves in order, looking after each one:
        cliff: walk to a bare rock face in sight
        peak: climb the highest ground in sight
        cave: walk down into the deepest cave opening in sight
        cavern: underground, walk through the cave to the far end of what is in sight
        roam: walk to one of the listed biomes in sight
        visit: walk to one of the listed structures in sight
        After the list it walks toward unexplored ground and starts over.
        Answer with the moves to try first, the biomes and structures that matter,
        and one short note. Use only real biome ids like plains or dark_forest.
        """;

    private static final String JUDGE = """
        You judge requests to a Minecraft Java 26.3 survival bot. It has no commands,
        no creative mode, and cannot leave the overworld. It mines, crafts, smelts,
        farms, and hunts, and finds raw things with these moves, looking after each one:
        cliff, peak, cave, cavern, roam (to listed biomes), visit (to listed structures).
        Say whether the request can be done in an ordinary survival overworld. If it
        can, give the moves, biomes, and structures that find the raw thing, and when
        it drops from mining a block, the ids of those blocks. The reason is one short
        sentence a player would understand.
        """;

    private final ModelClient router;
    private final String model;
    private final Logger log;
    private final ModelFires fires;

    public Advisor(ModelClient router, String model, Logger log, ModelFires fires) {
        this.router = router;
        this.model = model;
        this.log = log;
        this.fires = fires;
    }

    public boolean enabled() {
        return router.enabled();
    }

    public record Gap(String thing, String known, String biomeHere, List<String> biomesInSight,
                      List<String> structuresInSight, int y, String problem) {
        String briefing() {
            return """
                Looking for: %s
                What it knows: %s
                Standing at y=%d in %s.
                Biomes in sight: %s
                Structures in sight: %s
                What went wrong: %s
                """.formatted(thing, known, y, biomeHere, none(biomesInSight), none(structuresInSight), problem);
        }

        private static String none(List<String> names) {
            return names.isEmpty() ? "none" : String.join(", ", names);
        }
    }

    /**
     * Whether a request can be done, why, and when it can, where to look and
     * which blocks drop it.
     */
    public record Verdict(boolean possible, String reason, Optional<Lesson> lesson, Set<Material> blocks) {
    }

    /** Asked before a task the helper has no plan or knowledge for. Empty when there is no model or the call failed. */
    public Optional<Verdict> judge(Gap gap, Predicate<String> knownBiome, Set<String> structures) {
        if (!router.enabled()) {
            return Optional.empty();
        }
        try {
            fires.calling(false, "advice", model);
            ModelClient.Answer answer = router.ask(model, JUDGE, gap.briefing(), "verdict", verdictSchema(structures));
            fires.fired(false, "advice", model, answer.usage(), "verdict");
            JsonObject body = answer.body();
            String reason = body.get("reason").getAsString();
            return Optional.of(new Verdict(body.get("possible").getAsBoolean(), reason,
                lesson(body, reason, knownBiome, structures), blocks(body.getAsJsonArray("blocks"))));
        } catch (IOException | RuntimeException e) {
            log.warning("Judging call failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** Empty when there is no model, the call failed, or nothing valid came back. */
    public Optional<Lesson> advise(Gap gap, Predicate<String> knownBiome, Set<String> structures) {
        if (!router.enabled()) {
            return Optional.empty();
        }
        try {
            fires.calling(false, "advice", model);
            ModelClient.Answer answer = router.ask(model, SYSTEM, gap.briefing(), "lesson", schema(structures));
            fires.fired(false, "advice", model, answer.usage(), "lesson");
            JsonObject body = answer.body();
            return lesson(body, body.get("note").getAsString(), knownBiome, structures);
        } catch (IOException | RuntimeException e) {
            log.warning("Advice call failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    /** The moves, biomes, and structures of an answer, kept only where they exist. */
    private static Optional<Lesson> lesson(JsonObject answer, String note, Predicate<String> knownBiome,
                                           Set<String> structures) {
        List<Way> ways = new ArrayList<>();
        for (String name : strings(answer.getAsJsonArray("ways"))) {
            Way.parse(name).filter(way -> way.advisable() && !ways.contains(way)).ifPresent(ways::add);
        }
        List<String> biomes = strings(answer.getAsJsonArray("biomes")).stream().filter(knownBiome).toList();
        List<String> visit = strings(answer.getAsJsonArray("visit")).stream().filter(structures::contains).toList();
        return ways.isEmpty() ? Optional.empty() : Optional.of(new Lesson(ways, biomes, visit, note, 0, 0));
    }

    /** The names that are real blocks. */
    private static Set<Material> blocks(JsonArray names) {
        return strings(names).stream()
            .map(Material::matchMaterial)
            .filter(block -> block != null && block.isBlock() && !block.isAir())
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Material.class)));
    }

    private static String verdictSchema(Set<String> structures) {
        return """
            {
              "type": "object",
              "properties": {
                "possible": {"type": "boolean"},
                "reason": {"type": "string", "description": "One short sentence"},
                %s,
                "blocks": {"type": "array", "items": {"type": "string"},
                           "description": "Block ids that drop it when mined, like amethyst_cluster; empty if none"}
              },
              "required": ["possible", "reason", "ways", "biomes", "visit", "blocks"],
              "additionalProperties": false
            }
            """.formatted(placeProperties(structures));
    }

    private static String schema(Set<String> structures) {
        return """
            {
              "type": "object",
              "properties": {
                %s,
                "note": {"type": "string", "description": "One short sentence"}
              },
              "required": ["ways", "biomes", "visit", "note"],
              "additionalProperties": false
            }
            """.formatted(placeProperties(structures));
    }

    private static String placeProperties(Set<String> structures) {
        String ways = Arrays.stream(Way.values()).filter(Way::advisable).map(way -> "\"" + way.key() + "\"")
            .collect(Collectors.joining(", "));
        String visit = structures.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
        return """
            "ways": {"type": "array", "items": {"type": "string", "enum": [%s]}},
            "biomes": {"type": "array", "items": {"type": "string"}},
            "visit": {"type": "array", "items": {"type": "string", "enum": [%s]}}""".formatted(ways, visit);
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>();
        array.forEach(element -> values.add(element.getAsString()));
        return values;
    }
}
