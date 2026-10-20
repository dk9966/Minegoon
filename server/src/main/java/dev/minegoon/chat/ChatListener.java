package dev.minegoon.chat;

import dev.minegoon.MainThread;
import dev.minegoon.Session;
import dev.minegoon.agent.FrontDesk;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.List;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Reads chat. A ! line is a command, and so is a plain sentence such as
 * "get me 5 diamonds". Anything else that mentions the helper by name goes
 * to its front desk, which asks a model. One helper hears every player.
 * Several helpers each hear the world they are standing in.
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
        Commands commands = session.commands();
        if (text.startsWith("!") || commands.understands(text)) {
            Thread.ofVirtual().start(() -> commands.handle(speaker(player), text));
        } else if (session.desk().enabled() && mentions(session, text)) {
            FrontDesk desk = session.desk();
            Thread.ofVirtual().start(() -> desk.hear(speaker(player), text));
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

    private boolean mentions(Session session, String text) {
        Pattern mention = Pattern.compile("\\b" + Pattern.quote(session.helper().name()) + "\\b", Pattern.CASE_INSENSITIVE);
        return mention.matcher(text).find();
    }

    private Commands.Speaker speaker(Player player) {
        return main.call(() -> new Commands.Speaker(player.getUniqueId(), player.getName(), player.getLocation()));
    }
}
