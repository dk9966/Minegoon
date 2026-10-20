package dev.minegoon.jobs;

import com.google.gson.JsonObject;
import dev.minegoon.actions.Receipt;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;

/**
 * A spoken request that a model works through one tool call at a time, such
 * as "get 4 logs and 4 cobblestone, then come back to me". The log has a line
 * for each call with its receipt, and a line for anything that happened in
 * between, such as a death or the player changing the request.
 */
public record Mission(long id, UUID owner, String ownerName, String request, Task.Status status, String reason,
                      String world, double homeX, double homeY, double homeZ, Plan plan, List<Line> log,
                      int modelCalls, int strongCalls, double cost, long createdAt) {

    /** The steps the planner wrote, and the checks that say the whole request is done. */
    public record Plan(List<String> steps, List<Check> doneWhen) {
    }

    /** Something the game can check: the helper holds so many of an item, or stands near a player. */
    public record Check(Kind kind, String item, int count, String player, int within) {

        public enum Kind {
            HAVE, NEAR
        }

        public static Check have(String item, int count) {
            return new Check(Kind.HAVE, item, count, null, 0);
        }

        public static Check near(String player, int within) {
            return new Check(Kind.NEAR, null, 0, player, within);
        }

        public String describe() {
            return switch (kind) {
                case HAVE -> "have " + count + " " + item;
                case NEAR -> "within " + within + " blocks of " + player;
            };
        }
    }

    /** A tool call and, once it ends, its receipt; or a note. A call that fetches records its task. */
    public record Line(String tool, JsonObject args, Long task, Receipt receipt, String note) {

        public static Line call(String tool, JsonObject args) {
            return new Line(tool, args, null, null, null);
        }

        public static Line note(String text) {
            return new Line(null, null, null, null, text);
        }

        public boolean isCall() {
            return tool != null;
        }

        public boolean failed() {
            return receipt != null && !receipt.confirmed();
        }

        Line closed(Receipt ending) {
            return new Line(tool, args, task, ending, note);
        }

        Line fetching(long taskId) {
            return new Line(tool, args, taskId, receipt, note);
        }

        public String describe() {
            if (!isCall()) {
                return "note: " + note;
            }
            return tool + " " + args + " -> " + (receipt == null ? "still running" : receipt);
        }
    }

    public Location home() {
        return new Location(Bukkit.getWorld(world), homeX, homeY, homeZ);
    }

    public String describe() {
        return "\"" + request + "\"";
    }

    /** The calls the model has made so far. */
    public int turns() {
        return (int) log.stream().filter(Line::isCall).count();
    }

    /** The last call, while it has no receipt: it was running when the work stopped. */
    public Optional<Line> pending() {
        return log.reversed().stream().filter(Line::isCall).findFirst().filter(line -> line.receipt() == null);
    }

    /** The planner takes the next turn when there is no plan yet, the last call failed, or there is news. */
    public boolean needsPlanner() {
        if (plan == null || log.isEmpty()) {
            return true;
        }
        Line last = log.getLast();
        return !last.isCall() || last.failed();
    }
}
