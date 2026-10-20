package dev.minegoon.chat;

import dev.minegoon.ItemSet;
import dev.minegoon.agent.FrontDesk;
import dev.minegoon.jobs.Foreman;
import dev.minegoon.jobs.Mission;
import dev.minegoon.jobs.MissionBoard;
import dev.minegoon.jobs.Step;
import dev.minegoon.jobs.Task;
import dev.minegoon.planner.Planner;
import dev.minegoon.spawn.Helper;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

/**
 * /minegoon, for the console and operators: the same commands as chat, a
 * sentence for the front desk as if said in chat, a dry-run planner, recent
 * results, and a reset for practice runs.
 */
public final class AdminCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of(
        "get", "status", "inventory", "stop", "pause", "resume", "come", "give", "protect", "allow", "watch", "back",
        "ask", "plan", "results", "reset");

    private final Helper helper;
    private final Commands commands;
    private final FrontDesk desk;
    private final Foreman foreman;
    private final MissionBoard missions;
    private final Peek peek;
    private final boolean lab;

    public AdminCommand(Helper helper, Commands commands, FrontDesk desk, Foreman foreman, MissionBoard missions,
                        Peek peek, boolean lab) {
        this.helper = helper;
        this.commands = commands;
        this.desk = desk;
        this.foreman = foreman;
        this.missions = missions;
        this.peek = peek;
        this.lab = lab;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            return false;
        }
        switch (args[0]) {
            case "ask" -> ask(sender, Arrays.copyOfRange(args, 1, args.length));
            case "inventory", "inv" -> inventory(sender);
            case "plan" -> plan(sender, Arrays.copyOfRange(args, 1, args.length));
            case "results" -> results(sender);
            case "reset" -> reset(sender);
            default -> commands.handle(speaker(sender), "!" + String.join(" ", args));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 ? SUBCOMMANDS.stream().filter(s -> s.startsWith(args[0])).toList() : List.of();
    }

    private void ask(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /minegoon ask <sentence>");
            return;
        }
        if (!desk.enabled()) {
            sender.sendMessage("Ask needs an OpenRouter key in config.yml.");
            return;
        }
        Commands.Speaker speaker = speaker(sender);
        String text = String.join(" ", args);
        Thread.ofVirtual().start(() -> desk.hear(speaker, text));
    }

    private void inventory(CommandSender sender) {
        if (sender instanceof Player) {
            commands.handle(speaker(sender), "!inventory");
            return;
        }
        peek.report().forEach(sender::sendMessage);
    }

    private void plan(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[0].matches("\\d+")) {
            sender.sendMessage("Usage: /minegoon plan <count> <item>");
            return;
        }
        Material goal = ItemSet.parse(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (goal == null) {
            sender.sendMessage("Unknown item.");
            return;
        }
        try {
            List<Step> steps = foreman.preview(goal, Integer.parseInt(args[0]));
            for (int i = 0; i < steps.size(); i++) {
                sender.sendMessage((i + 1) + ". " + steps.get(i).describe());
            }
        } catch (Planner.Unplannable e) {
            sender.sendMessage("No plan: " + e.getMessage());
        }
    }

    private void results(CommandSender sender) {
        for (Mission mission : missions.recent(5)) {
            sender.sendMessage("Mission #" + mission.id() + " " + mission.describe() + ": " + mission.status()
                + (mission.reason() == null ? "" : " (" + mission.reason() + ")") + ", turns " + mission.turns()
                + ", planner calls " + mission.strongCalls() + ", cost $%.4f".formatted(mission.cost()));
        }
        List<Task> tasks = foreman.recent(10);
        if (tasks.isEmpty()) {
            sender.sendMessage("No tasks yet.");
        }
        for (Task task : tasks) {
            sender.sendMessage("#" + task.id() + " " + task.describe() + ": " + task.status()
                + (task.reason() == null ? "" : " (" + task.reason() + ")") + ", deaths " + task.deaths()
                + ", model calls " + task.modelCalls());
        }
    }

    private void reset(CommandSender sender) {
        if (!lab) {
            sender.sendMessage("Reset only works on the practice server (lab: true in config.yml).");
            return;
        }
        foreman.stop();
        helper.reset();
        sender.sendMessage("Helper reset to spawn.");
    }

    private Commands.Speaker speaker(CommandSender sender) {
        if (sender instanceof Player player) {
            return new Commands.Speaker(player.getUniqueId(), player.getName(), player.getLocation());
        }
        return new Commands.Speaker(null, sender.getName(), helper.online()
            ? helper.player().getLocation() : helper.spawnPoint());
    }
}
