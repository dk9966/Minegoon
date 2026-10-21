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
 * Reads chat. A sentence for the helper, such as "get me 5 diamonds" or one
 * that names it, goes to its front desk: a model reads it and picks the code
 * to run. Three kinds skip the model: a ! line, which runs that command as
 * typed; a plain stop, pause, resume or come, which must act at once; and,
 * with no key, any plain command. One helper hears every player. Several
 * helpers each hear the world they are standing in.
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
        FrontDesk desk = session.desk();
        if (text.startsWith("!") || commands.controls(text) || (!desk.enabled() && commands.understands(text))) {
            Thread.ofVirtual().start(() -> commands.handle(speaker(player), text));
        } else if (desk.enabled() && (commands.understands(text) || mentions(session, text))) {
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
