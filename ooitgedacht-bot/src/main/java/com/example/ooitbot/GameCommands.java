package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Commando's die de Minecraft-server nodig hebben: /spelers, /deaths en de
 * beheer-commando's /msg, /kick, /ban, /unban. Slaapt de server, dan krijg je
 * daar netjes een melding van (en bij /deaths de laatst bekende ranglijst).
 */
final class GameCommands {

    private static final int COLOR_PLAYERS = 0x5865F2;
    private static final int COLOR_DEATHS = 0x992D22;
    private static final String[] MEDALS = {":first_place:", ":second_place:", ":third_place:"};
    private static final String SLEEPING =
            ":zzz: De server slaapt nu. Join de server om hem wakker te maken, dan werkt dit weer.";

    private final Bot bot;
    private final int topSize;

    GameCommands(Bot bot, int topSize) {
        this.bot = bot;
        this.topSize = topSize;
    }

    static List<SlashCommandData> definitions(DefaultMemberPermissions staffOnly) {
        OptionData playerOnline = new OptionData(OptionType.STRING, "speler", "Naam van de speler (moet online zijn)", true, true);
        OptionData playerAny = new OptionData(OptionType.STRING, "speler", "Naam van de speler", true, true);
        OptionData reason = new OptionData(OptionType.STRING, "reden", "Waarom?", false).setMaxLength(200);

        return Arrays.asList(
                Commands.slash("spelers", "Bekijk wie er nu online is op de Minecraft-server."),
                Commands.slash("deaths", "Wie is er het vaakst doodgegaan op de server?")
                        .addOption(OptionType.STRING, "speler", "Bekijk de doden van één speler", false, true),
                Commands.slash("msg", "Stuur een speler in-game een bericht als beheer.")
                        .addOptions(playerOnline,
                                new OptionData(OptionType.STRING, "bericht", "Het bericht", true).setMaxLength(256))
                        .setDefaultPermissions(staffOnly),
                Commands.slash("kick", "Kick een speler van de Minecraft-server.")
                        .addOptions(playerOnline, reason)
                        .setDefaultPermissions(staffOnly),
                Commands.slash("ban", "Verban een speler van de Minecraft-server.")
                        .addOptions(playerAny, reason)
                        .setDefaultPermissions(staffOnly),
                Commands.slash("unban", "Hef een ban van een speler op.")
                        .addOptions(new OptionData(OptionType.STRING, "speler", "Naam van de speler", true))
                        .setDefaultPermissions(staffOnly)
        );
    }

    /** @return true als dit commando hier afgehandeld is. */
    boolean handle(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "spelers": spelers(event); return true;
            case "deaths": deaths(event); return true;
            case "msg":
            case "kick":
            case "ban":
            case "unban":
                staffAction(event);
                return true;
            default:
                return false;
        }
    }

    private void spelers(SlashCommandInteractionEvent event) {
        if (!bot.link.isOnline()) {
            event.replyEmbeds(bot.embed(COLOR_PLAYERS, ":busts_in_silhouette: Spelers online: 0", SLEEPING).build()).queue();
            return;
        }
        List<String> names = bot.link.players();
        List<String> escaped = new ArrayList<>();
        for (String n : names) escaped.add(MarkdownSanitizer.escape(n));
        String text = names.isEmpty() ? "Er is nu niemand online." : String.join(", ", escaped);
        event.replyEmbeds(bot.embed(COLOR_PLAYERS, ":busts_in_silhouette: Spelers online: "
                + names.size() + "/" + bot.link.maxPlayers(), text).build()).queue();
    }

    private void deaths(SlashCommandInteractionEvent event) {
        String only = event.getOption("speler", OptionMapping::getAsString);
        event.deferReply().queue();

        bot.worker.execute(() -> {
            if (!bot.link.isOnline()) {
                // Laatst bekende ranglijst laten zien, als we die hebben.
                JsonNode cached = bot.data.node("last-deaths");
                if (only == null && cached != null) {
                    reply(event, ":skull: Meeste doden (laatst bekend)", formatTop(cached) + "\n\n" + SLEEPING);
                } else {
                    reply(event, ":skull: Doden", SLEEPING);
                }
                return;
            }

            try {
                ObjectNode request = bot.link.request("deaths").put("limit", topSize);
                if (only != null) request.put("player", only);
                JsonNode response = bot.link.rpc(request);

                if (response.has("error")) {
                    reply(event, ":skull: Doden", response.path("error").asText());
                } else if (response.has("player")) {
                    String name = response.path("player").asText();
                    int deaths = response.path("deaths").asInt();
                    reply(event, ":skull: Doden van " + name, "**" + MarkdownSanitizer.escape(name) + "** is **"
                            + deaths + "x** doodgegaan." + (deaths == 0 ? " Netjes! :sparkles:" : ""));
                } else {
                    JsonNode top = response.path("top");
                    bot.data.putNode("last-deaths", top);
                    bot.data.save();
                    reply(event, ":skull: Meeste doden", formatTop(top));
                }
            } catch (IOException e) {
                reply(event, ":skull: Doden", ":x: De server reageert niet: " + e.getMessage());
            }
        });
    }

    private String formatTop(JsonNode top) {
        if (top.size() == 0) return "Nog niemand is doodgegaan. Hoe lang houden jullie dit vol? :eyes:";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(topSize, top.size()); i++) {
            JsonNode entry = top.get(i);
            sb.append(i < MEDALS.length ? MEDALS[i] : "`" + (i + 1) + ".`").append(" **")
                    .append(MarkdownSanitizer.escape(entry.path("name").asText())).append("** — ")
                    .append(entry.path("deaths").asInt()).append(" keer\n");
        }
        return sb.toString();
    }

    private void reply(SlashCommandInteractionEvent event, String title, String text) {
        event.getHook().editOriginalEmbeds(bot.embed(COLOR_DEATHS, title, text).build()).queue();
    }

    private void staffAction(SlashCommandInteractionEvent event) {
        if (!bot.isStaff(event.getMember())) {
            event.reply(":x: Alleen de eigenaar en beheerders mogen dit commando gebruiken.").setEphemeral(true).queue();
            return;
        }
        if (!bot.link.isOnline()) {
            event.reply(SLEEPING).setEphemeral(true).queue();
            return;
        }

        // Ephemeral = alleen jij ziet het antwoord.
        event.deferReply(true).queue();

        ObjectNode request = bot.link.request(event.getName())
                .put("by", event.getMember().getEffectiveName())
                .put("player", event.getOption("speler", "", OptionMapping::getAsString));
        String message = event.getOption("bericht", OptionMapping::getAsString);
        if (message != null) request.put("message", message);
        String reason = event.getOption("reden", OptionMapping::getAsString);
        if (reason != null) request.put("reason", reason);

        bot.worker.execute(() -> {
            String text;
            try {
                text = bot.link.rpc(request).path("text").asText(":x: Geen antwoord van de server.");
            } catch (IOException e) {
                text = ":x: De server reageert niet: " + e.getMessage();
            }
            event.getHook().editOriginal(text).queue();
        });
    }
}
