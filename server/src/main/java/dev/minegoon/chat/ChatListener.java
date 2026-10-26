package dev.minegoon.chat;

import dev.minegoon.MainThread;
import dev.minegoon.Session;
import dev.minegoon.agent.FrontDesk;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Reads chat. A line for the helper starts with !. One command, such as
 * "!get 5 diamond" or "!come here", runs as code. Anything else after the !,
 * such as "!what are you carrying?" or "!I need a bucket and some torches",
 * goes to the front desk when a key is set. Chat with no ! is left alone,
 * and saying the helper's name does not address it. One helper hears every
 * player. Several helpers each hear the world they are standing in.
 */
public final class ChatListener implements Listener {

    private final MainThread main;
    private final List<Session> sessions;

    public ChatListener(MainThread main, List<Session> sessions) {
        this.main = main;
        this.sessions = sessions;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (sessions.stream().anyMatch(session -> session.is(player))) {
            return;
        }
        Session session = sessionFor(player);
        if (session == null || session.commands() == null) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (!text.startsWith("!")) {
            return;
        }
        Commands commands = session.commands();
        FrontDesk desk = session.desk();
        Thread.ofVirtual().start(() -> hear(player, text, commands, desk));
    }

    private void hear(Player player, String text, Commands commands, FrontDesk desk) {
        Commands.Speaker speaker = speaker(player);
        String sentence = text.substring(1).trim();
        if (sentence.isEmpty() || commands.understands(text)) {
            commands.handle(speaker, sentence.isEmpty() ? "!help" : text);
        } else if (desk.enabled()) {
            desk.hear(speaker, sentence);
        } else {
            commands.missed();
        }
    }

    /** A single helper is the only one to talk to. Several each keep to their own world. */
    private Session sessionFor(Player player) {
        List<Session> online = sessions.stream().filter(Session::online).toList();
        if (online.size() == 1) {
            return online.getFirst();
        }
        return online.stream()
            .filter(session -> session.helper().player().getWorld().equals(player.getWorld()))
            .findFirst()
            .orElse(null);
    }

    private Commands.Speaker speaker(Player player) {
        return main.call(() -> new Commands.Speaker(player.getUniqueId(), player.getName(), player.getLocation()));
    }
}
