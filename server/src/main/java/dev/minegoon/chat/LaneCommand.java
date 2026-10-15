package dev.minegoon.chat;

import dev.minegoon.Session;
import dev.minegoon.world.Pos;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

/** /minegoon lane <world> <command>, so each shared trip is addressed on its own. */
public final class LaneCommand implements TabExecutor {

    private final Map<String, Session> lanes;

    public LaneCommand(Map<String, Session> lanes) {
        this.lanes = lanes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 2 || !args[0].equals("lane")) {
            sender.sendMessage("Usage: /minegoon lane <world> <command>");
            return true;
        }
        Session session = lanes.get(args[1]);
        if (session == null) {
            sender.sendMessage("No lane named " + args[1] + ".");
            return true;
        }
        if (args.length == 2) {
            sender.sendMessage("Usage: /minegoon lane " + args[1] + " <command>");
            return true;
        }
        switch (args[2]) {
            case "unload" -> session.unload(sender);
            case "load" -> session.load(sender);
            case "reset" -> session.prepare(sender, args.length > 3 && args[3].equals("clear"));
            case "spar" -> spar(sender, session, args);
            case "goto" -> go(sender, session, args);
            default -> session.admin().onCommand(sender, command, label, Arrays.copyOfRange(args, 2, args.length));
        }
        return true;
    }

    private static void spar(CommandSender sender, Session session, String[] args) {
        if (args.length < 4 || (args.length > 4 && !args[4].matches("[1-8]"))) {
            sender.sendMessage("Usage: /minegoon lane " + args[1] + " spar <monster> [1-8]");
            return;
        }
        session.spar(sender, args[3], args.length > 4 ? Integer.parseInt(args[4]) : 1);
    }

    /** goto x z walks to the surface there; goto x y z to that exact block. */
    private static void go(CommandSender sender, Session session, String[] args) {
        int[] numbers = Arrays.stream(args, 3, args.length).filter(part -> part.matches("-?\\d+"))
            .mapToInt(Integer::parseInt).toArray();
        if (numbers.length != args.length - 3 || numbers.length < 2 || numbers.length > 3) {
            sender.sendMessage("Usage: /minegoon lane " + args[1] + " goto <x> [y] <z>");
            return;
        }
        int x = numbers[0];
        int z = numbers[numbers.length - 1];
        session.go(sender, x, numbers.length == 3 ? numbers[1] : null, z);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return List.of("lane");
        }
        if (args.length == 2) {
            return lanes.keySet().stream().filter(name -> name.startsWith(args[1])).toList();
        }
        return List.of();
    }
}
