package dev.minegoon.chat;

import dev.minegoon.MainThread;
import dev.minegoon.Session;
import dev.minegoon.models.IntentParser;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Reads chat. A ! line is a command, and so is a plain sentence such as
 * "get me 5 diamonds". Anything else that mentions the helper by name goes
 * to the model. One helper hears every player. Several helpers each hear
 * the world they are standing in.
 */
public final class ChatListener implements Listener {

    private final MainThread main;
    private final List<Session> sessions;
    private final IntentParser parser;
    private final Logger log;

    public ChatListener(MainThread main, List<Session> sessions, IntentParser parser, Logger log) {
        this.main = main;
        this.sessions = sessions;
        this.parser = parser;
        this.log = log;
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
        } else if (parser.enabled() && mentions(session, text)) {
            Thread.ofVirtual().start(() -> interpret(session, player, text));
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

    private void interpret(Session session, Player player, String text) {
        try {
            parser.toCommand(text).ifPresent(line -> session.commands().handle(speaker(player), line));
        } catch (IOException e) {
            log.warning("Could not read chat with the model: " + e.getMessage());
            session.helper().say("Sorry, I didn't catch that. Try get 5 diamond.");
        }
    }

    private Commands.Speaker speaker(Player player) {
        return main.call(() -> new Commands.Speaker(player.getUniqueId(), player.getName(), player.getLocation()));
    }
}
