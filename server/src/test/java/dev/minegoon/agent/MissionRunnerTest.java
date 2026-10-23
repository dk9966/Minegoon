package dev.minegoon.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.minegoon.Database;
import dev.minegoon.Settings;
import dev.minegoon.actions.Receipt;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.Mission.Check;
import dev.minegoon.jobs.Mission.Line;
import dev.minegoon.jobs.MissionBoard;
import dev.minegoon.jobs.Task;
import dev.minegoon.models.ModelFires;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiPredicate;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MissionRunnerTest {

    private static final String PLAN = "{\"steps\": [\"do the work\"], \"done_when\": []}";
    private static final Settings.Agent AGENT = new Settings.Agent("planner", "cheap", 40, 12, 90);

    @TempDir
    Path folder;

    private Database db;
    private MissionBoard missions;
    private final List<Mission> ended = new ArrayList<>();
    private final List<String> said = new ArrayList<>();
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final List<Long> carried = new ArrayList<>();
    private final List<String> fires = new ArrayList<>();
    private BiPredicate<Check, Mission> met = (check, mission) -> true;

    @BeforeEach
    void open() throws SQLException {
        db = new Database(folder.resolve("minegoon.db"));
        missions = new MissionBoard(db, ended::add);
        fires.clear();
    }

    @AfterEach
    void close() throws SQLException {
        db.close();
    }

    @Test
    void thePlannerTakesTheFirstTurnAndTheTurnAfterAFailure() {
        ScriptedModel model = new ScriptedModel()
            .then("write_plan", PLAN)
            .then("work", "{}")
            .then("break", "{}")
            .then("work", "{}")
            .then("finish", "{\"summary\": \"All done.\"}");
        long id = run(model, AGENT);

        assertEquals(List.of("planner", "cheap", "cheap", "planner", "cheap"), model.models);
        assertEquals(List.of("big planner", "small desk", "small desk", "big planner", "small desk"), fires);
        Mission mission = missions.mission(id).orElseThrow();
        assertEquals(Task.Status.DONE, mission.status());
        assertEquals(5, mission.modelCalls());
        assertEquals(2, mission.strongCalls());
        assertEquals(5 * ScriptedModel.COST, mission.cost(), 1e-9);
        assertEquals(List.of("My plan for \"get 4 logs\":", "do the work", "All done."), said);
        assertEquals(List.of(id), ended.stream().map(Mission::id).toList());
    }

    @Test
    void aPlanIsStagesFromHereToTheGoalAndWhatToDoWhenThingsChange() {
        ScriptedModel model = new ScriptedModel()
            .then("write_plan", """
                {"stages": [
                  {"aim": "Wooden tools", "get": ["4 oak_log", "1 crafting_table", "1 wooden_pickaxe"],
                   "where": "the forest nearby", "do": ["chop logs", "craft"], "watch_for": ["falling from trees"]},
                  {"aim": "Iron pickaxe", "get": ["3 iron_ingot"], "where": "caves near y=14, toward the mountains",
                   "do": ["mine iron", "smelt it"], "watch_for": ["lava: step back"]}],
                 "if_then": ["night falls: dig in or sleep", "If health is low, back off and eat"],
                 "done_when": []}""")
            .then("finish", "{\"summary\": \"All done.\"}");
        long id = run(model, AGENT);

        List<String> steps = missions.mission(id).orElseThrow().plan().steps();
        assertEquals(List.of(
            "Stage 1: Wooden tools. Get: 4 oak_log, 1 crafting_table, 1 wooden_pickaxe. Where: the forest nearby. "
                + "Do: chop logs; craft. Watch for: falling from trees.",
            "Stage 2: Iron pickaxe. Get: 3 iron_ingot. Where: caves near y=14, toward the mountains. "
                + "Do: mine iron; smelt it. Watch for: lava: step back.",
            "If night falls: dig in or sleep",
            "If health is low, back off and eat"), steps);
        assertEquals("My plan for \"get 4 logs\":", said.getFirst());
    }

    @Test
    void theFirstTurnOffersOnlyPlanningAndOtherCallsAreTurnedDown() {
        ScriptedModel model = new ScriptedModel()
            .then("work", "{}")
            .then("write_plan", PLAN)
            .then("finish", "{\"summary\": \"Done.\"}");
        long id = run(model, AGENT);

        List<String> offered = new ArrayList<>();
        model.offered.getFirst().forEach(spec -> offered.add(spec.getAsJsonObject().getAsJsonObject("function")
            .get("name").getAsString()));
        assertEquals(List.of("write_plan", "give_up"), offered);
        Mission mission = missions.mission(id).orElseThrow();
        assertTrue(mission.log().getFirst().receipt().observed().startsWith("Write the plan first"));
        assertEquals(List.of("planner", "planner", "cheap"), model.models);
        assertEquals(Task.Status.DONE, mission.status());
    }

    @Test
    void finishWithAnUnmetCheckReopensTheMissionForThePlanner() {
        AtomicInteger asked = new AtomicInteger();
        met = (check, mission) -> asked.incrementAndGet() > 1;
        ScriptedModel model = new ScriptedModel()
            .then("write_plan", "{\"steps\": [\"get logs\"], \"done_when\": [{\"kind\": \"near\", \"within\": 4}]}")
            .then("finish", "{\"summary\": \"Here.\"}")
            .then("finish", "{\"summary\": \"Here now.\"}");
        long id = run(model, AGENT);

        Mission mission = missions.mission(id).orElseThrow();
        assertEquals(List.of(Check.near("Steve", 4)), mission.plan().doneWhen());
        Receipt reopened = mission.log().get(1).receipt();
        assertFalse(reopened.confirmed());
        assertTrue(reopened.observed().startsWith("Not done yet: within 4 blocks of Steve."), reopened.observed());
        assertEquals(List.of("planner", "cheap", "planner"), model.models);
        assertEquals(Task.Status.DONE, mission.status());
        assertEquals("Here now.", mission.reason());
    }

    @Test
    void runningOutOfTurnsBlocksIt() {
        ScriptedModel model = new ScriptedModel().then("write_plan", PLAN).then("work", "{}").then("work", "{}");
        long id = run(model, new Settings.Agent("planner", "cheap", 3, 12, 90));
        assertBlocked(id, "I used all 3 turns");
    }

    @Test
    void needingThePlannerTooOftenBlocksIt() {
        ScriptedModel model = new ScriptedModel()
            .then("write_plan", PLAN)
            .then("break", "{\"try\": 1}")
            .then("break", "{\"try\": 2}");
        long id = run(model, new Settings.Agent("planner", "cheap", 40, 2, 90));
        assertBlocked(id, "I needed to rethink more than 2 times");
    }

    @Test
    void runningPastTheClockBlocksIt() {
        ScriptedModel model = new ScriptedModel().then("write_plan", PLAN).then("wait", "{}");
        long id = run(model, AGENT);
        assertBlocked(id, "it has taken more than 90 minutes");
    }

    @Test
    void failingTwiceTheSameWayBlocksIt() {
        ScriptedModel model = new ScriptedModel().then("write_plan", PLAN).then("break", "{}").then("break", "{}");
        long id = run(model, AGENT);
        assertBlocked(id, "break failed twice the same way: The pick broke.");
    }

    @Test
    void anUnreachableModelBlocksIt() {
        long id = run(new ScriptedModel(), AGENT);
        assertBlocked(id, "the model could not be reached: the script ran out");
    }

    @Test
    void aMissionCarriesOnFromItsSavedLog() {
        long id = mission();
        missions.setPlan(id, new Mission.Plan(List.of("get logs"), List.of()));
        missions.append(id, Line.call("write_plan", JsonParser.parseString(PLAN).getAsJsonObject()));
        missions.close(id, Receipt.confirmed("write_plan", "Plan saved."));
        missions.append(id, Line.call("fetch", JsonParser.parseString("{\"item\": \"oak_log\"}").getAsJsonObject()));
        missions.fetching(id, 7);

        ScriptedModel model = new ScriptedModel().then("finish", "{\"summary\": \"Got them.\"}");
        runner(model, AGENT).run(id);

        assertEquals(List.of(7L), carried);
        assertEquals(List.of("cheap"), model.models);
        Mission mission = missions.mission(id).orElseThrow();
        assertTrue(mission.log().get(1).receipt().confirmed());
        assertEquals(Task.Status.DONE, mission.status());
    }

    @Test
    void aCallCutShortOrADeathGivesThePlannerTheNextTurn() {
        long id = mission();
        missions.setPlan(id, new Mission.Plan(List.of("fight"), List.of()));
        missions.append(id, Line.call("work", JsonParser.parseString("{}").getAsJsonObject()));
        missions.append(id, Line.note("died at 10 64 -20"));

        ScriptedModel model = new ScriptedModel().then("finish", "{\"summary\": \"Done.\"}");
        runner(model, AGENT).run(id);

        Mission mission = missions.mission(id).orElseThrow();
        assertEquals("Interrupted before it finished.", mission.log().getFirst().receipt().observed());
        assertEquals(List.of("planner"), model.models);
        assertTrue(model.briefings.getFirst().contains("note: died at 10 64 -20"), model.briefings.getFirst());
    }

    @Test
    void aMissionThatIsNotActiveIsLeftAlone() {
        long id = mission();
        missions.setStatus(id, Task.Status.PAUSED, "called over by a player");
        ScriptedModel model = new ScriptedModel();
        runner(model, AGENT).run(id);
        assertTrue(model.models.isEmpty());
    }

    @Test
    void theBriefingIsFreshAndShort() {
        ScriptedModel model = new ScriptedModel()
            .then("write_plan", PLAN)
            .then("work", "{}")
            .then("finish", "{\"summary\": \"Done.\"}");
        run(model, AGENT);
        String last = model.briefings.getLast();
        assertTrue(last.startsWith("Request from Steve: \"get 4 logs\"\nPlan:\n1. do the work\n"), last);
        assertTrue(last.contains("work {} -> ok work: Worked. Now at 0 64 0."), last);
        assertTrue(last.endsWith("Now at 0 64 0.\nTurn 3 of 40."), last);
    }

    private void assertBlocked(long id, String reason) {
        Mission mission = missions.mission(id).orElseThrow();
        assertEquals(Task.Status.BLOCKED, mission.status());
        assertTrue(mission.reason().startsWith(reason), mission.reason());
        assertTrue(said.getLast().startsWith("I'm stopping \"get 4 logs\""), said.getLast());
    }

    private long run(ScriptedModel model, Settings.Agent agent) {
        long id = mission();
        runner(model, agent).run(id);
        return id;
    }

    private long mission() {
        return missions.create(null, "Steve", new Location(world(), 0, 64, 0), "get 4 logs");
    }

    private MissionRunner runner(ScriptedModel model, Settings.Agent agent) {
        List<Tool<Mission>> tools = List.of(
            Tool.of("work", "always works", (args, mission) -> Receipt.confirmed("work", "Worked.")),
            Tool.of("break", "always fails", (args, mission) -> Receipt.refused("break", "The pick broke.")),
            Tool.of("wait", "takes a long time", (args, mission) -> {
                clock.addAndGet(91 * 60_000L);
                return Receipt.confirmed("wait", "Waited.");
            }),
            Tool.of("fetch", "carries on with a task", (args, mission) -> {
                carried.add(mission.pending().orElseThrow().task());
                return Receipt.confirmed("fetch", "Holding 4 oak_log.");
            }));
        ModelFires recorded = new ModelFires(
            fire -> fires.add((fire.big() ? "big " : "small ") + fire.role()), null);
        return new MissionRunner(model, agent, missions, tools, () -> "at 0 64 0", (check, m) -> met.test(check, m),
            said::add, clock::get, "Builder", recorded);
    }

    /** Only the name is read: the runner never touches the world. */
    private static World world() {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "world";
                case "hashCode" -> 1;
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }
}
