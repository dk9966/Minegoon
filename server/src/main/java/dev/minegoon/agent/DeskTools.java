package dev.minegoon.agent;

import dev.minegoon.actions.Receipt;
import dev.minegoon.chat.Commands;
import dev.minegoon.chat.Commands.Speaker;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.MissionBoard;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The tools of the front desk: one cheap turn per sentence aimed at the
 * helper. They answer, act at once, or hand the request to a mission. None of
 * them moves the helper themselves, so they run on the chat thread. What a
 * tool observed is what the helper says back.
 */
public final class DeskTools {

    static final List<String> COMMANDS = List.of("come", "give", "stop", "pause", "resume", "protect", "allow",
        "watch", "chase", "free", "back");

    private final Commands commands;
    private final Foreman foreman;
    private final MissionBoard missions;
    private final Senses senses;

    public DeskTools(Commands commands, Foreman foreman, MissionBoard missions, Senses senses) {
        this.commands = commands;
        this.foreman = foreman;
        this.missions = missions;
        this.senses = senses;
    }

    public List<Tool<Speaker>> all() {
        return List.of(answer(), status(), senses.inventoryTool(), senses.lookAroundTool(), command(), get(),
            startMission(), amendMission());
    }

    private Tool<Speaker> answer() {
        return Tool.of("answer", """
            Answers in chat: a question, a greeting, or why a request cannot be done in survival Minecraft.""", """
            {"type": "object", "properties": {"text": {"type": "string"}}, "required": ["text"]}""",
            (args, speaker) -> Receipt.confirmed("answer", args.text("text")));
    }

    private Tool<Speaker> status() {
        return Tool.of("status", "Says what the helper is working on and how far it has got.",
            (args, speaker) -> Receipt.confirmed("status", String.join(" ", foreman.status())));
    }

    private Tool<Speaker> command() {
        String names = COMMANDS.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
        return Tool.of("command", """
            Does one of the instant commands. come: walk to the speaker. give: hand over what the last task got. \
            stop: drop all work. pause and resume. protect: never dig within radius blocks of the speaker. \
            allow: may dig player-made blocks within radius for the current task. watch: let the speaker see \
            through the helper's eyes. chase: carry the speaker's camera behind and above the helper, in third \
            person. free: stop following but stay in spectator where the camera is. back: end watching and \
            return the speaker to where they started.""", """
            {"type": "object", "properties": {
              "name": {"type": "string", "enum": [%s]},
              "radius": {"type": "integer", "minimum": 1, "maximum": 128, "description": "For protect and allow"}
            }, "required": ["name"]}""".formatted(names), (args, speaker) -> {
                String name = args.text("name");
                if (!COMMANDS.contains(name)) {
                    throw new Args.Refused("there is no command called " + name);
                }
                int radius = args.number("radius", 1, 128, 0);
                commands.handle(speaker, "!" + name + (radius == 0 ? "" : " " + radius));
                return Receipt.confirmed("command", "");
            });
    }

    private Tool<Speaker> get() {
        return Tool.of("get", """
            Fetches a number of one item and brings it to the speaker. Only for a request that is just an item \
            and a number, with nothing said about how or where to look and nothing else to do.""", """
            {"type": "object", "properties": {
              "item": {"type": "string", "description": "A Minecraft item id, such as oak_log"},
              "count": {"type": "integer", "minimum": 1, "maximum": %d,
                        "description": "a few is 3, some is 8, a stack is 64"}
            }, "required": ["item", "count"]}""".formatted(Commands.MAX_COUNT), (args, speaker) -> {
                int count = args.count("count");
                String item = args.text("item");
                commands.handle(speaker, "!get " + count + " " + item);
                return Receipt.confirmed("get", "");
            });
    }

    private Tool<Speaker> startMission() {
        return Tool.of("start_mission", """
            Starts work on anything that needs the helper to act and is more than a plain get: several items, \
            a way or place to search, or something to do after, such as coming back. Restate the request in \
            full, with every detail the speaker gave.""", """
            {"type": "object", "properties": {"request": {"type": "string"}}, "required": ["request"]}""",
            (args, speaker) -> {
                foreman.undertake(speaker.id(), speaker.name(), speaker.location(), args.text("request"));
                return Receipt.confirmed("start_mission", "On it.");
            });
    }

    private Tool<Speaker> amendMission() {
        return Tool.of("amend_mission", """
            Adds what the speaker just said to the mission that is running, such as "and bring a bucket too". \
            It is read before the mission's next step. To drop the work now, use the stop command instead.""", """
            {"type": "object", "properties": {"change": {"type": "string"}}, "required": ["change"]}""",
            (args, speaker) -> {
                Mission mission = missions.current().orElseThrow(() -> new Args.Refused("there is no mission to change"));
                missions.append(mission.id(), Mission.Line.note(speaker.name() + " added: " + args.text("change")));
                return Receipt.confirmed("amend_mission", "Got it.");
            });
    }
}
