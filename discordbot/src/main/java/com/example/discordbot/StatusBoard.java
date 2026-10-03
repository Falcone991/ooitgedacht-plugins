package com.example.discordbot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Bouwt het status-bericht voor het statuskanaal: server-info plus de fase
 * van de SurvivalTimeline-plugin. Moet op de hoofdthread aangeroepen worden.
 *
 * Tijden staan erin als Discord-tijdstempels (<t:...:R>). Die rekent Discord
 * zelf live om naar "over 3 dagen" / "2 uur geleden", ook tussen updates door.
 */
class StatusBoard {

    private static final int COLOR_ONLINE = 0x57F287;
    private static final int COLOR_OFFLINE = 0xED4245;
    private static final long DAY_MS = TimeUnit.DAYS.toMillis(1);

    private final String title;
    private final String description;
    private final String serverAddress;
    private final String footer;

    StatusBoard(String title, String description, String serverAddress, String footer) {
        this.title = title;
        this.description = description;
        this.serverAddress = serverAddress;
        this.footer = footer;
    }

    MessageEmbed build(boolean online) {
        EmbedBuilder eb = new EmbedBuilder()
                .setColor(online ? COLOR_ONLINE : COLOR_OFFLINE)
                .setTitle(title)
                .setTimestamp(Instant.now())
                .setFooter(footer.isEmpty() ? "Laatste update" : footer + " \u2022 Laatste update");
        if (!description.isEmpty()) eb.setDescription(description);

        eb.addField("Server", serverSection(online), false);

        if (online) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) names.add(MarkdownSanitizer.escape(p.getName()));
            if (!names.isEmpty()) {
                names.sort(String.CASE_INSENSITIVE_ORDER);
                eb.addField("Wie is er online?", cut(String.join(", ", names), 1024), false);
            }
        }

        String timeline = timelineSection();
        if (timeline != null) {
            eb.addField("Survival-tijdlijn", timeline, false);
        }

        return eb.build();
    }

    private String serverSection(boolean online) {
        StringBuilder sb = new StringBuilder();
        if (online) {
            long jvmStart = ManagementFactory.getRuntimeMXBean().getStartTime();
            sb.append("**Status:** Online :white_check_mark:\n");
            sb.append("**Spelers:** ").append(Bukkit.getOnlinePlayers().size())
                    .append("/").append(Bukkit.getMaxPlayers()).append("\n");
            sb.append("**Online sinds:** ").append(ts(jvmStart, 'R')).append("\n");
            sb.append("**TPS:** ").append(tpsText());
        } else {
            sb.append("**Status:** Offline :x:\n");
            sb.append("**Offline sinds:** ").append(ts(System.currentTimeMillis(), 'R'));
        }
        if (!serverAddress.isEmpty()) {
            sb.append("\n**IP:** `").append(serverAddress.replace("`", "")).append("`");
        }
        return sb.toString();
    }

    private static String tpsText() {
        double tps = Math.min(20.0, Bukkit.getTPS()[0]);
        String icon = tps >= 18 ? ":white_check_mark:" : tps >= 15 ? ":warning:" : ":x:";
        return String.format(Locale.ROOT, "%.1f %s", tps, icon);
    }

    /**
     * Leest de status rechtstreeks uit de SurvivalTimeline-plugin (config +
     * data.yml), zodat die plugin zelf niet aangepast hoeft te worden.
     * null als SurvivalTimeline niet geïnstalleerd is.
     */
    private String timelineSection() {
        Plugin timeline = Bukkit.getPluginManager().getPlugin("SurvivalTimeline");
        if (timeline == null) return null;

        FileConfiguration cfg = timeline.getConfig();
        File dataFile = new File(timeline.getDataFolder(), "data.yml");
        if (!dataFile.exists()) return null;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (!data.contains("start-time")) return null;

        long start = data.getLong("start-time");
        boolean hardcore = data.getBoolean("hardcore-activated", false);
        boolean nether = data.getBoolean("nether-unlocked", false);
        boolean end = data.getBoolean("end-unlocked", false);

        int weeksBeforeHardcore = cfg.getInt("weeks-before-hardcore", 3);
        int netherOpenWeek = cfg.getInt("nether-open-week", 2);
        int endOpenWeek = cfg.getInt("end-open-week", 3);

        // Zelfde rekenwijze als SurvivalTimeline: week 1 = dag 0 t/m 6, week 2 begint op dag 7, enz.
        long elapsedDays = Math.max(0, (System.currentTimeMillis() - start) / DAY_MS);
        long week = elapsedDays / 7 + 1;

        long hardcoreAt = start + weeksBeforeHardcore * 7L * DAY_MS;
        long netherAt = start + (netherOpenWeek - 1) * 7L * DAY_MS;
        long endAt = start + (endOpenWeek - 1) * 7L * DAY_MS;

        StringBuilder sb = new StringBuilder();
        sb.append("**Fase:** ").append(hardcore
                ? ":skull: Hardcore \u2014 sterven is definitief"
                : ":seedling: Genadeperiode \u2014 respawnen kan nog").append("\n");
        sb.append("**Week:** ").append(week).append(" (dag ").append(elapsedDays + 1).append(")\n");
        sb.append("**Gestart:** ").append(ts(start, 'D')).append("\n");
        sb.append("**Hardcore:** ").append(hardcore
                ? "actief sinds " + ts(hardcoreAt, 'D')
                : "begint " + ts(hardcoreAt, 'R')).append("\n");
        sb.append("**Nether:** ").append(nether ? "open :white_check_mark:" : ":lock: opent " + ts(netherAt, 'R')).append("\n");
        sb.append("**End:** ").append(end ? "open :white_check_mark:" : ":lock: opent " + ts(endAt, 'R'));

        World world = mainWorld(cfg.getString("main-world", ""));
        if (world != null) {
            sb.append("\n**Wereldgrens:** ").append(Math.round(world.getWorldBorder().getSize())).append(" blokken");
        }
        return sb.toString();
    }

    private static World mainWorld(String name) {
        if (name != null && !name.isEmpty()) return Bukkit.getWorld(name);
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0);
    }

    /** Discord-tijdstempel. Stijl 'R' = relatief ("over 2 dagen"), 'D' = datum. */
    private static String ts(long millis, char style) {
        return "<t:" + (millis / 1000) + ":" + style + ">";
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
