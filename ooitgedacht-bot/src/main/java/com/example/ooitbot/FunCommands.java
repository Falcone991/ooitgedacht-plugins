package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leuke commando's voor iedereen die géén server nodig hebben, en dus ook
 * werken als de server slaapt: /streak, /streaks en de nugget-spelletjes
 * (zelfde als de FunItems-plugin in-game; teksten staan in de bot-config).
 */
final class FunCommands {

    private static final int COLOR_GOLD = 0xF1C40F;
    private static final int COLOR_WIN = 0x57F287;
    private static final int COLOR_LOSE = 0xED4245;
    private static final int COLOR_DRAW = 0xFEE75C;
    private static final int COLOR_IRON_LOSE = 0x95A5A6;
    private static final int COLOR_STREAK = 0xE67E22;

    private static final String[] RPS = {"Steen", "Papier", "Schaar"};
    private static final String[] MEDALS = {":first_place:", ":second_place:", ":third_place:"};

    private final Bot bot;
    private final ZoneId zone;
    private final int topSize;
    private final Random random = new Random();
    // Cooldowns per Discord-gebruiker per spelletje (sleutel "userId:spel" -> tijdstip ms).
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    FunCommands(Bot bot, ZoneId zone, int topSize) {
        this.bot = bot;
        this.zone = zone;
        this.topSize = topSize;
    }

    static List<SlashCommandData> commands() {
        return Arrays.asList(
                Commands.slash("streak", "Typ dit elke dag om je streak op te bouwen!"),
                Commands.slash("streaks", "Bekijk wie de langste streak heeft."),
                Commands.slash("goldnugget", "Kop of munt!"),
                Commands.slash("coppernugget", "Steen, papier, schaar tegen de server.")
                        .addOptions(new OptionData(OptionType.STRING, "keuze", "Wat kies je? (leeg = willekeurig)", false)
                                .addChoice("Steen", "Steen")
                                .addChoice("Papier", "Papier")
                                .addChoice("Schaar", "Schaar")),
                Commands.slash("ironnugget", "Waag een gok voor de jackpot!")
        );
    }

    /** @return true als dit commando hier afgehandeld is. */
    boolean handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "streak": streak(event); return true;
            case "streaks": streaks(event); return true;
            case "goldnugget": goldNugget(event); return true;
            case "coppernugget": copperNugget(event); return true;
            case "ironnugget": ironNugget(event); return true;
            default: return false;
        }
    }

    // ---------- /streak en /streaks ----------

    private void streak(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        long today = LocalDate.now(zone).toEpochDay();
        String nextChance = "<t:" + startOfDay(today + 1) + ":R>";

        int count;
        int best;
        int previous;
        boolean alreadyToday;
        boolean continued;
        synchronized (bot.data) {
            ObjectNode streaks = bot.data.object("streaks");
            JsonNode entry = streaks.get(userId);
            long lastDay = entry != null ? entry.path("last-day").asLong(Long.MIN_VALUE) : Long.MIN_VALUE;
            count = entry != null ? entry.path("count").asInt(0) : 0;
            best = entry != null ? entry.path("best").asInt(0) : 0;
            previous = count;

            alreadyToday = lastDay == today;
            continued = lastDay == today - 1;
            if (!alreadyToday) {
                count = continued ? count + 1 : 1;
                best = Math.max(best, count);
                streaks.putObject(userId).put("count", count).put("best", best).put("last-day", today);
            }
        }

        if (alreadyToday) {
            event.replyEmbeds(bot.embed(COLOR_STREAK, ":fire: Al gedaan vandaag!",
                    "Je hebt je streak vandaag al bijgehouden.\nJe kunt weer " + nextChance + ".")
                    .addField("Huidige streak", days(count), true)
                    .addField("Beste streak", days(best), true)
                    .build()).queue();
            return;
        }
        bot.data.save();

        String text;
        if (continued) {
            text = "Je streak is verlengd naar **" + days(count) + "**! :fire:";
        } else if (previous > 0) {
            text = "Oei, je streak van " + days(previous) + " is verlopen. :broken_heart:\n"
                    + "Nieuwe streak gestart: **1 dag**.";
        } else {
            text = "Je eerste streak is begonnen: **1 dag**! Kom morgen terug om hem te verlengen.";
        }
        if (count == 7 || count == 30 || count == 100 || count == 365) {
            text += "\n\n:tada: **Mijlpaal: " + days(count) + " op rij!** :tada:";
        }

        event.replyEmbeds(bot.embed(COLOR_STREAK, ":fire: Streak van " + event.getUser().getEffectiveName(), text)
                .addField("Huidige streak", days(count), true)
                .addField("Beste streak", days(best), true)
                .addField("Volgende keer", nextChance, true)
                .build()).queue();
    }

    private void streaks(SlashCommandInteractionEvent event) {
        long today = LocalDate.now(zone).toEpochDay();

        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        synchronized (bot.data) {
            ObjectNode streaks = bot.data.object("streaks");
            for (Iterator<Map.Entry<String, JsonNode>> it = streaks.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                // Alleen streaks die nog "leven" (vandaag of gisteren bijgehouden).
                long lastDay = e.getValue().path("last-day").asLong(Long.MIN_VALUE);
                int count = e.getValue().path("count").asInt(0);
                if (lastDay >= today - 1 && count > 0) list.add(Map.entry(e.getKey(), count));
            }
        }
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        StringBuilder sb = new StringBuilder();
        if (list.isEmpty()) sb.append("Nog niemand heeft een streak. Wees de eerste met `/streak`!");
        for (int i = 0; i < Math.min(topSize, list.size()); i++) {
            Map.Entry<String, Integer> e = list.get(i);
            // <@id> in een embed toont de naam, maar pingt niemand.
            sb.append(i < MEDALS.length ? MEDALS[i] : "`" + (i + 1) + ".`")
                    .append(" <@").append(e.getKey()).append("> — ").append(days(e.getValue())).append("\n");
        }
        event.replyEmbeds(bot.embed(COLOR_STREAK, ":fire: Langste streaks", sb.toString()).build()).queue();
    }

    private long startOfDay(long epochDay) {
        return LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toEpochSecond();
    }

    private static String days(int n) {
        return n + (n == 1 ? " dag" : " dagen");
    }

    // ---------- Nugget-spelletjes (zelfde als FunItems in-game) ----------

    private String cfg(String path, String def) {
        return bot.config.text("fun." + path, def);
    }

    /** "&6&lKop!" -> "Kop!" (Minecraft-kleurcodes werken niet in Discord). */
    private static String strip(String text) {
        return text.replaceAll("(?i)[&§][0-9a-fk-orx]", "");
    }

    /** true (en antwoord gestuurd) als deze gebruiker nog moet wachten. */
    private boolean onCooldown(SlashCommandInteractionEvent event, String game, int seconds) {
        if (seconds <= 0) return false;
        String key = event.getUser().getId() + ":" + game;
        long now = System.currentTimeMillis();
        Long last = cooldowns.get(key);
        if (last != null && now - last < seconds * 1000L) {
            event.reply(":hourglass: Even wachten voordat je dit weer doet.").setEphemeral(true).queue();
            return true;
        }
        cooldowns.put(key, now);
        return false;
    }

    private boolean disabled(SlashCommandInteractionEvent event, String section) {
        if (bot.config.bool("fun." + section + ".enabled", true)) return false;
        event.reply(":no_entry: Dit spelletje staat nu uit.").setEphemeral(true).queue();
        return true;
    }

    private EmbedBuilder withUser(EmbedBuilder eb, SlashCommandInteractionEvent event) {
        return eb.setAuthor(event.getUser().getEffectiveName(), null, event.getUser().getEffectiveAvatarUrl());
    }

    private void goldNugget(SlashCommandInteractionEvent event) {
        if (disabled(event, "gold-coinflip")) return;
        if (onCooldown(event, "gold", bot.config.integer("fun.gold-coinflip.cooldown-seconds", 1))) return;

        String message = random.nextBoolean()
                ? cfg("gold-coinflip.heads-message", "&6&lKop!")
                : cfg("gold-coinflip.tails-message", "&6&lMunt!");
        event.replyEmbeds(withUser(bot.embed(COLOR_GOLD, ":coin: Gouden nugget", "**" + strip(message) + "**"), event)
                .build()).queue();
    }

    private void copperNugget(SlashCommandInteractionEvent event) {
        if (disabled(event, "copper-rps")) return;
        if (onCooldown(event, "copper", bot.config.integer("fun.copper-rps.cooldown-seconds", 1))) return;

        String chosen = event.getOption("keuze", OptionMapping::getAsString);
        int yours = chosen != null ? Arrays.asList(RPS).indexOf(chosen) : random.nextInt(3);
        if (yours < 0) yours = random.nextInt(3);
        int server = random.nextInt(3);

        String template;
        int color;
        if (yours == server) {
            template = cfg("copper-rps.draw-message", "&eJij: {you} vs Server: {opponent} &6-> Gelijkspel!");
            color = COLOR_DRAW;
        } else if ((yours + 1) % 3 == server) {
            // Steen(0) verliest van Papier(1), Papier(1) van Schaar(2), Schaar(2) van Steen(0).
            template = cfg("copper-rps.lose-message", "&cJij: {you} vs Server: {opponent} &4-> Verloren!");
            color = COLOR_LOSE;
        } else {
            template = cfg("copper-rps.win-message", "&aJij: {you} vs Server: {opponent} &2-> Gewonnen!");
            color = COLOR_WIN;
        }

        String message = template.replace("{you}", RPS[yours]).replace("{opponent}", RPS[server]);
        event.replyEmbeds(withUser(bot.embed(color, ":rock: :page_facing_up: :scissors: Koperen nugget",
                "**" + strip(message) + "**"), event).build()).queue();
    }

    private void ironNugget(SlashCommandInteractionEvent event) {
        if (disabled(event, "iron-gamble")) return;
        if (onCooldown(event, "iron", bot.config.integer("fun.iron-gamble.cooldown-seconds", 3))) return;

        int outOf = Math.max(1, bot.config.integer("fun.iron-gamble.win-chance-out-of", 10));
        boolean win = random.nextInt(outOf) == 0;

        EmbedBuilder eb = win
                ? bot.embed(COLOR_WIN, ":tada: IJzeren nugget — JACKPOT!",
                        "**" + strip(cfg("iron-gamble.win-message", "&a&lJACKPOT!")) + "**")
                : bot.embed(COLOR_IRON_LOSE, ":nut_and_bolt: IJzeren nugget",
                        strip(cfg("iron-gamble.lose-message", "&7Helaas, geen geluk.")));
        event.replyEmbeds(withUser(eb, event).build()).queue();
    }
}
