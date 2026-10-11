package com.example.ooitbot;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * /english: stuurt de Engelse uitleg over de server privé (DM) naar wie het typt.
 *
 * De tekst staat in english.txt naast de bot (wordt bij de eerste keer uit de jar
 * gezet) en wordt elke keer opnieuw gelezen, dus aanpassen kan zonder herstart.
 * Een regel met alleen "---" begint een nieuw bericht (Discord: max. 2000 tekens per bericht).
 * Staan iemands DM's dicht, dan komt de tekst als "alleen jij ziet dit"-antwoord.
 */
final class EnglishCommand {

    private static final int MAX_MESSAGE = 2000;
    private final Path file = Paths.get("").toAbsolutePath().resolve("english.txt");

    static SlashCommandData command() {
        return Commands.slash("english", "Get the server info in English (sent to you privately).");
    }

    void handle(SlashCommandInteractionEvent event) {
        List<String> parts;
        try {
            parts = parts(load());
        } catch (IOException e) {
            Log.warn("Kon english.txt niet lezen: " + e.getMessage());
            event.reply(":x: The English info is not available right now.").setEphemeral(true).queue();
            return;
        }
        if (parts.isEmpty()) {
            event.reply(":x: The English info is empty.").setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue(hook -> event.getUser().openPrivateChannel().queue(
                channel -> sendAll(parts, 0, text -> channel.sendMessage(text), () ->
                                hook.editOriginal(":incoming_envelope: I've sent you the info in a private message!").queue(),
                        err -> fallback(hook, parts)),
                err -> fallback(hook, parts)));
    }

    /** DM's dicht: dan als antwoord dat alleen de speler ziet. */
    private static void fallback(InteractionHook hook, List<String> parts) {
        hook.editOriginal(":lock: I couldn't send you a private message (your DMs are closed), so here it is:").queue();
        sendAll(parts, 0, text -> hook.sendMessage(text).setEphemeral(true), () -> { }, err ->
                Log.warn("Kon /english niet sturen: " + err.getMessage()));
    }

    private interface Sender {
        net.dv8tion.jda.api.requests.RestAction<?> send(String text);
    }

    /** Berichten één voor één, in volgorde. */
    private static void sendAll(List<String> parts, int index, Sender sender, Runnable done,
                                java.util.function.Consumer<Throwable> failed) {
        if (index >= parts.size()) {
            done.run();
            return;
        }
        sender.send(parts.get(index)).queue(ok -> sendAll(parts, index + 1, sender, done, failed), failed);
    }

    private String load() throws IOException {
        if (!Files.exists(file)) {
            try (InputStream in = EnglishCommand.class.getResourceAsStream("/english.txt")) {
                if (in == null) throw new IOException("english.txt ontbreekt in de jar");
                Files.copy(in, file);
            }
        }
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    /** Opdelen op regels met alleen "---", en te lange stukken op regelgrenzen. */
    private static List<String> parts(String text) {
        List<String> result = new ArrayList<>();
        for (String block : text.replace("\r\n", "\n").split("(?m)^---\\s*$")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty()) continue;
            StringBuilder current = new StringBuilder();
            for (String line : trimmed.split("\n", -1)) {
                if (current.length() + line.length() + 1 > MAX_MESSAGE && current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
                if (current.length() > 0) current.append('\n');
                current.append(line.length() > MAX_MESSAGE ? line.substring(0, MAX_MESSAGE) : line);
            }
            if (current.length() > 0) result.add(current.toString());
        }
        return result;
    }
}
