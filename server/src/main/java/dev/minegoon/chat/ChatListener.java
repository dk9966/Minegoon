package dev.minegoon.chat;

import dev.minegoon.MainThread;
import dev.minegoon.models.IntentParser;
import dev.minegoon.spawn.Helper;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.IOException;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/**
 * Reads chat. Lines starting with ! are commands; other lines that mention
 * the helper by name go to the model to be turned into a command.
 */
public final class ChatListener implements Listener {

    private final MainThread main;
    private final Helper helper;
    private final Commands commands;
    private final IntentParser parser;
    private final Logger log;
    private final Pattern mention;

    public ChatListener(MainThread main, Helper helper, Commands commands, IntentParser parser, Logger log) {
        this.main = main;
        this.helper = helper;
        this.commands = commands;
        this.parser = parser;
        this.log = log;
        this.mention = Pattern.compile("\\b" + Pattern.quote(helper.name()) + "\\b", Pattern.CASE_INSENSITIVE);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (helper.is(player)) {
            return;
        }
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (text.startsWith("!")) {
            Thread.ofVirtual().start(() -> commands.handle(speaker(player), text));
        } else if (parser.enabled() && mention.matcher(text).find()) {
            Thread.ofVirtual().start(() -> interpret(player, text));
        }
    }

    private void interpret(Player player, String text) {
        try {
            parser.toCommand(text).ifPresent(line -> commands.handle(speaker(player), line));
        } catch (IOException e) {
            log.warning("Could not read chat with the model: " + e.getMessage());
            helper.say("Sorry, I didn't catch that. Try a ! command, like !get 5 diamond.");
        }
    }

    private Commands.Speaker speaker(Player player) {
        return main.call(() -> new Commands.Speaker(player.getUniqueId(), player.getName(), player.getLocation()));
    }
}
