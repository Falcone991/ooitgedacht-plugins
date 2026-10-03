package com.example.discordbot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.Plugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Leuke Discord-commando's voor iedereen: /deaths, /streak, /streaks en de
 * nugget-spelletjes (/goldnugget, /coppernugget, /ironnugget).
 *
 * De nugget-spelletjes lezen hun teksten, kansen en cooldowns uit de config
 * van de FunItems-plugin, zodat Discord en in-game precies hetzelfde werken.
 */
class FunCommands {

    private static final int COLOR_GOLD = 0xF1C40F;
    private static final int COLOR_COPPER = 0xB87333;
    private static final int COLOR_WIN = 0x57F287;
    private static final int COLOR_LOSE = 0xED4245;
    private static final int COLOR_DRAW = 0xFEE75C;
    private static final int COLOR_IRON_LOSE = 0x95A5A6;
    private static final int COLOR_DEATHS = 0x992D22;
    private static final int COLOR_STREAK = 0xE67E22;

    private static final String[] RPS = {"Steen", "Papier", "Schaar"};
    private static final String[] MEDALS = {":first_place:", ":second_place:", ":third_place:"};

    private final DiscordBot bot;
    private final ZoneId zone;
    private final int topSize;
    private final Random random = new Random();
    // Cooldowns per Discord-gebruiker per spelletje (sleutel "userId:spel" -> tijdstip ms).
    private final Map<String, Long> cooldowns = new ConcurrentHashMap<>();

    FunCommands(DiscordBot bot, ZoneId zone, int topSize) {
        this.bot = bot;
        this.zone = zone;
        this.topSize = Math.max(3, Math.min(25, topSize));
    }

    static List<SlashCommandData> commands() {
        return Arrays.asList(
                Commands.slash("deaths", "Wie is er het vaakst doodgegaan op de server?")
                        .addOption(OptionType.STRING, "speler", "Bekijk de doden van één speler", false, true),
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
            case "deaths": deaths(event); return true;
            case "streak": streak(event); return true;
            case "streaks": streaks(event); return true;
            case "goldnugget": goldNugget(event); return true;
            case "coppernugget": copperNugget(event); return true;
            case "ironnugget": ironNugget(event); return true;
            default: return false;
        }
    }

    private void reply(SlashCommandInteractionEvent event, MessageEmbed embed) {
        event.getHook().editOriginalEmbeds(embed).queue();
    }

    // ---------- /deaths ----------

    private void deaths(SlashCommandInteractionEvent event) {
        String only = event.getOption("speler", OptionMapping::getAsString);
        event.deferReply().queue();

        // Statistieken van offline spelers worden van schijf gelezen: op de hoofdthread.
        bot.onMainThread(() -> {
            if (only != null) {
                OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(only);
                if (player == null) {
                    reply(event, bot.embed(COLOR_DEATHS, ":skull: Doden",
                            "**" + DiscordBot.md(only) + "** is nog nooit op de server geweest.").build());
                    return;
                }
                int deaths = player.getStatistic(Statistic.DEATHS);
                String name = player.getName() != null ? player.getName() : only;
                reply(event, bot.embed(COLOR_DEATHS, ":skull: Doden van " + name,
                        "**" + DiscordBot.md(name) + "** is **" + deaths + "x** doodgegaan."
                                + (deaths == 0 ? " Netjes! :sparkles:" : "")).build());
                return;
            }

            List<Map.Entry<String, Integer>> list = new ArrayList<>();
            for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
                if (player.getName() == null) continue;
                try {
                    int deaths = player.getStatistic(Statistic.DEATHS);
                    if (deaths > 0) list.add(Map.entry(player.getName(), deaths));
                } catch (Exception ignored) {
                    // Geen statistieken-bestand voor deze speler; overslaan.
                }
            }
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            StringBuilder sb = new StringBuilder();
            if (list.isEmpty()) {
                sb.append("Nog niemand is doodgegaan. Hoe lang houden jullie dit vol? :eyes:");
            }
            for (int i = 0; i < Math.min(topSize, list.size()); i++) {
                Map.Entry<String, Integer> e = list.get(i);
                sb.append(i < MEDALS.length ? MEDALS[i] : "`" + (i + 1) + ".`").append(" **")
                        .append(DiscordBot.md(e.getKey())).append("** — ").append(e.getValue())
                        .append(" keer").append("\n");
            }
            reply(event, bot.embed(COLOR_DEATHS, ":skull: Meeste doden", sb.toString()).build());
        });
    }

    // ---------- /streak en /streaks ----------

    private void streak(SlashCommandInteractionEvent event) {
        String userId = event.getUser().getId();
        event.deferReply().queue();

        // Streak-data staat in data.yml; die alleen op de hoofdthread aanraken.
        bot.onMainThread(() -> {
            FileConfiguration data = bot.data();
            String path = "streaks." + userId;
            long today = LocalDate.now(zone).toEpochDay();
            long lastDay = data.getLong(path + ".last-day", Long.MIN_VALUE);
            int count = data.getInt(path + ".count", 0);
            int best = data.getInt(path + ".best", 0);
            String nextChance = "<t:" + startOfDay(today + 1) + ":R>";

            if (lastDay == today) {
                reply(event, bot.embed(COLOR_STREAK, ":fire: Al gedaan vandaag!",
                        "Je hebt je streak vandaag al bijgehouden.\nJe kunt weer " + nextChance + ".")
                        .addField("Huidige streak", days(count), true)
                        .addField("Beste streak", days(best), true)
                        .build());
                return;
            }

            boolean continued = lastDay == today - 1;
            int previous = count;
            count = continued ? count + 1 : 1;
            best = Math.max(best, count);

            data.set(path + ".count", count);
            data.set(path + ".best", best);
            data.set(path + ".last-day", today);
            bot.saveData();

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

            reply(event, bot.embed(COLOR_STREAK, ":fire: Streak van " + event.getUser().getEffectiveName(), text)
                    .addField("Huidige streak", days(count), true)
                    .addField("Beste streak", days(best), true)
                    .addField("Volgende keer", nextChance, true)
                    .build());
        });
    }

    private void streaks(SlashCommandInteractionEvent event) {
        event.deferReply().queue();

        bot.onMainThread(() -> {
            long today = LocalDate.now(zone).toEpochDay();
            ConfigurationSection section = bot.data().getConfigurationSection("streaks");

            List<Map.Entry<String, Integer>> list = new ArrayList<>();
            if (section != null) {
                for (String userId : section.getKeys(false)) {
                    // Alleen streaks die nog "leven" (vandaag of gisteren bijgehouden).
                    long lastDay = section.getLong(userId + ".last-day", Long.MIN_VALUE);
                    int count = section.getInt(userId + ".count", 0);
                    if (lastDay >= today - 1 && count > 0) list.add(Map.entry(userId, count));
                }
            }
            list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            StringBuilder sb = new StringBuilder();
            if (list.isEmpty()) {
                sb.append("Nog niemand heeft een streak. Wees de eerste met `/streak`!");
            }
            for (int i = 0; i < Math.min(topSize, list.size()); i++) {
                Map.Entry<String, Integer> e = list.get(i);
                // <@id> in een embed toont de naam, maar pingt niemand.
                sb.append(i < MEDALS.length ? MEDALS[i] : "`" + (i + 1) + ".`")
                        .append(" <@").append(e.getKey()).append("> — ").append(days(e.getValue())).append("\n");
            }
            reply(event, bot.embed(COLOR_STREAK, ":fire: Langste streaks", sb.toString()).build());
        });
    }

    private long startOfDay(long epochDay) {
        return LocalDate.ofEpochDay(epochDay).atStartOfDay(zone).toEpochSecond();
    }

    private static String days(int n) {
        return n + (n == 1 ? " dag" : " dagen");
    }

    // ---------- Nugget-spelletjes (zelfde als FunItems in-game) ----------

    private FileConfiguration funItemsConfig() {
        Plugin funItems = Bukkit.getPluginManager().getPlugin("FunItems");
        return funItems != null ? funItems.getConfig() : null;
    }

    private static String cfg(FileConfiguration c, String path, String def) {
        return c == null ? def : c.getString(path, def);
    }

    private static int cfgInt(FileConfiguration c, String path, int def) {
        return c == null ? def : c.getInt(path, def);
    }

    private static boolean cfgBool(FileConfiguration c, String path, boolean def) {
        return c == null ? def : c.getBoolean(path, def);
    }

    /** "&6&lKop!" -> "Kop!" (Minecraft-kleurcodes werken niet in Discord). */
    private static String strip(String text) {
        return ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', text));
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

    private boolean disabled(SlashCommandInteractionEvent event, FileConfiguration c, String section) {
        if (cfgBool(c, section + ".enabled", true)) return false;
        event.reply(":no_entry: Dit spelletje staat nu uit.").setEphemeral(true).queue();
        return true;
    }

    private void goldNugget(SlashCommandInteractionEvent event) {
        FileConfiguration c = funItemsConfig();
        if (disabled(event, c, "gold-coinflip")) return;
        if (onCooldown(event, "gold", cfgInt(c, "gold-coinflip.cooldown-seconds", 1))) return;

        boolean heads = random.nextBoolean();
        String message = heads
                ? cfg(c, "gold-coinflip.heads-message", "&6&lKop!")
                : cfg(c, "gold-coinflip.tails-message", "&6&lMunt!");

        event.replyEmbeds(bot.embed(COLOR_GOLD, ":coin: Gouden nugget", "**" + strip(message) + "**")
                .setAuthor(event.getUser().getEffectiveName(), null, event.getUser().getEffectiveAvatarUrl())
                .build()).queue();
    }

    private void copperNugget(SlashCommandInteractionEvent event) {
        FileConfiguration c = funItemsConfig();
        if (disabled(event, c, "copper-rps")) return;
        if (onCooldown(event, "copper", cfgInt(c, "copper-rps.cooldown-seconds", 1))) return;

        String chosen = event.getOption("keuze", OptionMapping::getAsString);
        int yours = chosen != null ? Arrays.asList(RPS).indexOf(chosen) : random.nextInt(3);
        if (yours < 0) yours = random.nextInt(3);
        int server = random.nextInt(3);

        String template;
        int color;
        if (yours == server) {
            template = cfg(c, "copper-rps.draw-message", "&eJij: {you} vs Server: {opponent} &6-> Gelijkspel!");
            color = COLOR_DRAW;
        } else if ((yours + 1) % 3 == server) {
            // Steen(0) verliest van Papier(1), Papier(1) van Schaar(2), Schaar(2) van Steen(0).
            template = cfg(c, "copper-rps.lose-message", "&cJij: {you} vs Server: {opponent} &4-> Verloren!");
            color = COLOR_LOSE;
        } else {
            template = cfg(c, "copper-rps.win-message", "&aJij: {you} vs Server: {opponent} &2-> Gewonnen!");
            color = COLOR_WIN;
        }

        String message = template.replace("{you}", RPS[yours]).replace("{opponent}", RPS[server]);
        event.replyEmbeds(bot.embed(color, ":rock: :page_facing_up: :scissors: Koperen nugget", "**" + strip(message) + "**")
                .setAuthor(event.getUser().getEffectiveName(), null, event.getUser().getEffectiveAvatarUrl())
                .build()).queue();
    }

    private void ironNugget(SlashCommandInteractionEvent event) {
        FileConfiguration c = funItemsConfig();
        if (disabled(event, c, "iron-gamble")) return;
        if (onCooldown(event, "iron", cfgInt(c, "iron-gamble.cooldown-seconds", 3))) return;

        int outOf = Math.max(1, cfgInt(c, "iron-gamble.win-chance-out-of", 10));
        boolean win = random.nextInt(outOf) == 0;

        EmbedBuilder eb = win
                ? bot.embed(COLOR_WIN, ":tada: IJzeren nugget — JACKPOT!",
                        "**" + strip(cfg(c, "iron-gamble.win-message", "&a&lJACKPOT!")) + "**")
                : bot.embed(COLOR_IRON_LOSE, ":nut_and_bolt: IJzeren nugget",
                        strip(cfg(c, "iron-gamble.lose-message", "&7Helaas, geen geluk.")));
        event.replyEmbeds(eb
                .setAuthor(event.getUser().getEffectiveName(), null, event.getUser().getEffectiveAvatarUrl())
                .build()).queue();
    }
}
