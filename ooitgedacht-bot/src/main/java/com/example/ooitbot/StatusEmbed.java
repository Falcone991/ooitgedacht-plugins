package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Bouwt het statusbericht voor het statuskanaal: server-info plus de fase
 * van SurvivalTimeline.
 *
 * Tijden staan erin als Discord-tijdstempels (<t:...:R>). Die rekent Discord
 * zelf live om naar "over 3 dagen" / "2 uur geleden", ook tussen updates door.
 */
final class StatusEmbed {

    private static final int COLOR_ONLINE = 0x57F287;
    private static final int COLOR_SLEEPING = 0x5865F2;
    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

    private final String title;
    private final String description;
    private final String serverAddress;
    private final String footer;
    private final String offlineText;

    StatusEmbed(String title, String description, String serverAddress, String footer, String offlineText) {
        this.title = title;
        this.description = description;
        this.serverAddress = serverAddress;
        this.footer = footer;
        this.offlineText = offlineText;
    }

    /**
     * @param status      antwoord op "status" van de plugin, of null als de server slaapt
     * @param timeline    tijdlijn-info (actueel, of laatst bekend als de server slaapt), mag null zijn
     * @param sleepingSince tijdstip (ms) waarop de server in slaap ging
     */
    MessageEmbed build(JsonNode status, JsonNode timeline, long sleepingSince) {
        boolean online = status != null;
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(online ? COLOR_ONLINE : COLOR_SLEEPING)
                .setTitle(title)
                .setTimestamp(Instant.now())
                .setFooter(footer.isEmpty() ? "Laatste update" : footer + " • Laatste update");
        if (!description.isEmpty()) eb.setDescription(description);

        eb.addField("Server", online ? onlineSection(status) : sleepingSection(sleepingSince), false);

        if (online) {
            List<String> names = new ArrayList<>();
            status.path("players").forEach(n -> names.add(MarkdownSanitizer.escape(n.asText())));
            if (!names.isEmpty()) {
                eb.addField("Wie is er online?", cut(String.join(", ", names), 1024), false);
            }
        }

        if (timeline != null && timeline.has("start")) {
            eb.addField(online ? "Survival-tijdlijn" : "Survival-tijdlijn (laatst bekend)", timelineSection(timeline), false);
        }
        return eb.build();
    }

    private String onlineSection(JsonNode status) {
        double tps = status.path("tps").asDouble(20.0);
        String tpsIcon = tps >= 18 ? ":white_check_mark:" : tps >= 15 ? ":warning:" : ":x:";

        StringBuilder sb = new StringBuilder();
        sb.append("**Status:** Online :white_check_mark:\n");
        sb.append("**Spelers:** ").append(status.path("players").size())
                .append("/").append(status.path("max").asInt()).append("\n");
        if (status.has("started")) {
            sb.append("**Online sinds:** ").append(ts(status.path("started").asLong(), 'R')).append("\n");
        }
        sb.append("**TPS:** ").append(String.format(Locale.ROOT, "%.1f", tps)).append(" ").append(tpsIcon);
        appendAddress(sb);
        return sb.toString();
    }

    private String sleepingSection(long since) {
        StringBuilder sb = new StringBuilder();
        sb.append(offlineText.replace("\\n", "\n")).append("\n");
        sb.append("**Sinds:** ").append(ts(since, 'R'));
        appendAddress(sb);
        return sb.toString();
    }

    private void appendAddress(StringBuilder sb) {
        if (!serverAddress.isEmpty()) {
            sb.append("\n**IP:** `").append(serverAddress.replace("`", "")).append("`");
        }
    }

    private static String timelineSection(JsonNode t) {
        long start = t.path("start").asLong();
        boolean hardcore = t.path("hardcore").asBoolean();
        boolean nether = t.path("nether").asBoolean();
        boolean end = t.path("end").asBoolean();
        int weeksBeforeHardcore = t.path("weeksBeforeHardcore").asInt(3);
        int netherOpenWeek = t.path("netherOpenWeek").asInt(2);
        int endOpenWeek = t.path("endOpenWeek").asInt(3);

        // Zelfde rekenwijze als SurvivalTimeline: week 1 = dag 0 t/m 6, week 2 begint op dag 7, enz.
        long elapsedDays = Math.max(0, (System.currentTimeMillis() - start) / DAY_MS);
        long week = elapsedDays / 7 + 1;

        long hardcoreAt = start + weeksBeforeHardcore * 7L * DAY_MS;
        long netherAt = start + (netherOpenWeek - 1) * 7L * DAY_MS;
        long endAt = start + (endOpenWeek - 1) * 7L * DAY_MS;

        // Tijdens de slaap telt de tijdlijn gewoon door (echte weken). Een mijlpaal
        // die intussen gepasseerd is, wordt pas "officieel" als de server weer draait.
        long now = System.currentTimeMillis();

        StringBuilder sb = new StringBuilder();
        sb.append("**Fase:** ").append(hardcore || now >= hardcoreAt
                ? ":skull: Hardcore — sterven is definitief"
                : ":seedling: Genadeperiode — respawnen kan nog").append("\n");
        sb.append("**Week:** ").append(week).append(" (dag ").append(elapsedDays + 1).append(")\n");
        sb.append("**Gestart:** ").append(ts(start, 'D')).append("\n");
        sb.append("**Hardcore:** ").append(hardcore || now >= hardcoreAt
                ? "actief sinds " + ts(hardcoreAt, 'D')
                : "begint " + ts(hardcoreAt, 'R')).append("\n");
        sb.append("**Nether:** ").append(nether || now >= netherAt ? "open :white_check_mark:" : ":lock: opent " + ts(netherAt, 'R')).append("\n");
        sb.append("**End:** ").append(end || now >= endAt ? "open :white_check_mark:" : ":lock: opent " + ts(endAt, 'R'));
        if (t.has("border")) {
            sb.append("\n**Wereldgrens:** ").append(t.path("border").asLong()).append(" blokken");
        }
        return sb.toString();
    }

    /** Discord-tijdstempel. Stijl 'R' = relatief ("over 2 dagen"), 'D' = datum. */
    private static String ts(long millis, char style) {
        return "<t:" + (millis / 1000) + ":" + style + ">";
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
