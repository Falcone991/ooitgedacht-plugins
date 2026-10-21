package com.example.ooitbot;

import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.utils.FileUpload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Logs en crash-reports van de Minecraft-server als downloadbaar bestand in Discord.
 *
 *  - /logs [soort]: latest.log, de vorige log (van vóór de laatste herstart) of het
 *    nieuwste crash-report. Alleen voor beheer; het antwoord ziet alleen wie het vraagt.
 *  - Vanzelf in het log-kanaal: elk nieuw crash-report, en de vorige log als de server
 *    niet netjes is afgesloten (bv. hard gestopt door een geheugentekort). Dat zie je
 *    zodra de server weer opstart: Minecraft pakt dan de oude log in als .log.gz.
 *
 * De bot leest de bestanden direct van schijf (bot en server staan in dezelfde AMP).
 */
final class ServerLogs {

    private static final long MAX_BYTES = 8L * 1024 * 1024; // ruim onder de uploadlimiet van Discord

    private final Bot bot;
    private final Path folder;     // map van de Minecraft-server (met logs/ erin), of null
    private final String channelId;

    ServerLogs(Bot bot, String configuredFolder, String channelId) {
        this.bot = bot;
        this.channelId = channelId;
        this.folder = findServerFolder(configuredFolder);
        if (folder == null) {
            Log.warn("Map van de Minecraft-server niet gevonden; /logs en crash-meldingen werken niet. "
                    + "Vul server-logs.folder in (de map waar 'logs' en 'server.properties' in staan).");
        } else {
            Log.info("Server-logs worden gelezen uit " + folder + ".");
        }
    }

    String channelId() {
        return channelId;
    }

    // ---------- /logs ----------

    static SlashCommandData command(DefaultMemberPermissions staffOnly) {
        return Commands.slash("logs", "Download een log of crash-report van de Minecraft-server.")
                .addOptions(new OptionData(OptionType.STRING, "soort", "Welke log (standaard: de huidige)", false)
                        .addChoice("Huidige log (latest.log)", "latest")
                        .addChoice("Vorige log (van voor de laatste herstart)", "vorige")
                        .addChoice("Nieuwste crash-report", "crash"))
                .setDefaultPermissions(staffOnly);
    }

    void handle(SlashCommandInteractionEvent event) {
        if (folder == null) {
            event.reply(":x: De bot weet niet waar de server-logs staan. Vul `server-logs.folder` in de config in.")
                    .setEphemeral(true).queue();
            return;
        }
        OptionMapping option = event.getOption("soort");
        String kind = option == null ? "latest" : option.getAsString();
        event.deferReply(true).queue();
        bot.worker.submit(() -> {
            try {
                Upload upload = switch (kind) {
                    case "vorige" -> previousLog();
                    case "crash" -> newestCrashReport();
                    default -> file(folder.resolve("logs").resolve("latest.log"));
                };
                if (upload == null) {
                    event.getHook().sendMessage(":information_source: " + (kind.equals("crash")
                            ? "Er zijn nog geen crash-reports." : "Die log bestaat (nog) niet.")).queue();
                    return;
                }
                event.getHook().sendMessage(upload.note)
                        .addFiles(FileUpload.fromData(upload.data, upload.name))
                        .queue(null, err -> Log.warn("Kon log niet sturen: " + err.getMessage()));
            } catch (IOException e) {
                event.getHook().sendMessage(":x: Kon de log niet lezen: " + e.getMessage()).queue();
            }
        });
    }

    // ---------- Vanzelf melden ----------

    /** Elke minuut: nieuwe crash-reports en onverwachte stops naar het log-kanaal. */
    void check() throws IOException {
        if (folder == null) return;
        TextChannel channel = bot.channel(channelId);

        // Crash-reports: elk nieuw bestand één keer.
        Path crashDir = folder.resolve("crash-reports");
        if (Files.isDirectory(crashDir)) {
            boolean first = bot.data.text("seen-crash-reports") == null;
            Set<String> seen = new HashSet<>(Arrays.asList(bot.data.text("seen-crash-reports") == null
                    ? new String[0] : bot.data.text("seen-crash-reports").split("\n")));
            List<Path> fresh = new ArrayList<>();
            try (Stream<Path> files = Files.list(crashDir)) {
                files.filter(Files::isRegularFile).sorted().forEach(p -> {
                    if (seen.add(p.getFileName().toString())) fresh.add(p);
                });
            }
            if (!fresh.isEmpty() || first) {
                bot.data.putText("seen-crash-reports", String.join("\n", seen));
                bot.data.save();
            }
            if (!first && channel != null) {
                for (Path p : fresh) {
                    Upload upload = file(p);
                    channel.sendMessage(Bot.cut(":rotating_light: **De server is gecrasht.**\n"
                                    + crashReason(new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
                                    + "\nHet volledige crash-report staat hieronder:", 2000))
                            .addFiles(FileUpload.fromData(upload.data, upload.name))
                            .queue(null, err -> Log.warn("Kon crash-report niet sturen: " + err.getMessage()));
                }
            }
        }

        // Vorige log: netjes afgesloten? Zo niet, dan is de server hard gestopt.
        Path newest = newestArchivedLog();
        if (newest == null) return;
        String name = newest.getFileName().toString();
        String last = bot.data.text("last-checked-log");
        if (name.equals(last)) return;
        bot.data.putText("last-checked-log", name);
        bot.data.save();
        if (last == null || channel == null) return; // eerste keer: alleen onthouden

        byte[] text = gunzip(newest);
        String content = new String(text, StandardCharsets.UTF_8);
        if (content.contains("Stopping server") || content.contains("Stopping the server")) return;
        Upload upload = limit(text, name.replace(".gz", ""));
        channel.sendMessage(Bot.cut(":warning: **De server is onverwacht gestopt** (niet netjes afgesloten).\n"
                        + stopReason(content) + "\nDe log van vlak daarvoor staat hieronder:", 2000))
                .addFiles(FileUpload.fromData(upload.data, upload.name))
                .queue(null, err -> Log.warn("Kon log niet sturen: " + err.getMessage()));
    }

    // ---------- Waarom? ----------

    /** Uit een crash-report: de beschrijving en de fout eronder. */
    static String crashReason(String report) {
        String[] lines = report.split("\\R");
        String description = null;
        String error = null;
        for (int i = 0; i < lines.length; i++) {
            if (description == null && lines[i].startsWith("Description:")) {
                description = lines[i].substring("Description:".length()).trim();
                for (int j = i + 1; j < lines.length && error == null; j++) {
                    if (!lines[j].isBlank()) error = lines[j].trim();
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        if (description != null) sb.append("**Wat:** ").append(description).append("\n");
        if (error != null) sb.append("**Fout:** `").append(Bot.cut(error, 300)).append("`\n");
        sb.append(advice(report));
        return sb.toString();
    }

    /** Uit een log die niet netjes eindigde: wanneer, de laatste fouten, en wat het waarschijnlijk was. */
    static String stopReason(String log) {
        String[] lines = log.split("\\R");
        String lastTime = null;
        List<String> errors = new ArrayList<>();
        int from = Math.max(0, lines.length - 300);
        for (int i = lines.length - 1; i >= 0 && lastTime == null; i--) {
            if (lines[i].matches("^\\[\\d{2}:\\d{2}:\\d{2}].*")) lastTime = lines[i].substring(1, 9);
        }
        for (int i = from; i < lines.length; i++) {
            String line = lines[i];
            if (line.contains("/ERROR]") || line.contains("Exception") || line.contains("Error:")) {
                errors.add(Bot.cut(line.trim(), 200));
            }
        }
        StringBuilder sb = new StringBuilder();
        if (lastTime != null) sb.append("**Laatste regel in de log:** ").append(lastTime).append("\n");
        String advice = advice(log);
        if (!advice.isEmpty()) {
            sb.append(advice);
        } else {
            // Een harde stop schrijft niets meer in de log; losse foutregels ervoor zijn vaak onschuldig.
            sb.append("**Waarom:** de log stopt zonder afsluiten of crashmelding. De server is dus van buitenaf hard "
                    + "uitgezet. Meestal is dat een **geheugentekort** (de app in TrueNAS liep tegen zijn limiet), "
                    + "of iemand drukte in AMP/TrueNAS op Stop of Restart.\n");
        }
        if (!errors.isEmpty()) {
            sb.append("**Laatste foutregels** (kunnen ook onschuldig zijn):\n```\n");
            for (String e : errors.subList(Math.max(0, errors.size() - 3), errors.size())) sb.append(e).append("\n");
            sb.append("```\n");
        }
        return sb.toString();
    }

    /** Bekende oorzaken in gewone taal. */
    private static String advice(String text) {
        if (text.contains("OutOfMemoryError")) {
            return "**Waarschijnlijk:** het geheugen van Minecraft was vol (OutOfMemoryError).\n";
        }
        if (text.contains("Watchdog") || text.contains("The server has stopped responding")) {
            return "**Waarschijnlijk:** de server liep vast (reageerde te lang niet) en is gestopt.\n";
        }
        if (text.contains("StackOverflowError")) {
            return "**Waarschijnlijk:** een oneindige lus in een plugin of redstone (StackOverflowError).\n";
        }
        return "";
    }

    // ---------- Bestanden ----------

    private record Upload(byte[] data, String name, String note) {
    }

    private Upload file(Path path) throws IOException {
        if (!Files.isRegularFile(path)) return null;
        return limit(Files.readAllBytes(path), path.getFileName().toString());
    }

    private Upload previousLog() throws IOException {
        Path newest = newestArchivedLog();
        if (newest == null) return null;
        return limit(gunzip(newest), newest.getFileName().toString().replace(".gz", ""));
    }

    private Upload newestCrashReport() throws IOException {
        Path crashDir = folder.resolve("crash-reports");
        if (!Files.isDirectory(crashDir)) return null;
        try (Stream<Path> files = Files.list(crashDir)) {
            Path newest = files.filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(ServerLogs::modified)).orElse(null);
            return newest == null ? null : file(newest);
        }
    }

    private Path newestArchivedLog() throws IOException {
        Path logs = folder.resolve("logs");
        if (!Files.isDirectory(logs)) return null;
        try (Stream<Path> files = Files.list(logs)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".log.gz"))
                    .max(Comparator.comparingLong(ServerLogs::modified)).orElse(null);
        }
    }

    /** Te groot voor Discord? Dan alleen het laatste stuk (daar staat de fout meestal). */
    private static Upload limit(byte[] data, String name) {
        if (!name.endsWith(".log") && !name.endsWith(".txt")) name = name + ".txt";
        if (data.length <= MAX_BYTES) return new Upload(data, name, ":page_facing_up: `" + name + "`");
        byte[] tail = Arrays.copyOfRange(data, data.length - (int) MAX_BYTES, data.length);
        return new Upload(tail, name, ":page_facing_up: `" + name + "` (te groot, alleen het laatste stuk)");
    }

    private static byte[] gunzip(Path path) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(path))) {
            return in.readAllBytes();
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * De ingestelde map, of zelf zoeken: AMP zet alle instances naast elkaar in een
     * map "instances"; daarin zoeken we de instance met een Minecraft/logs-map.
     */
    private static Path findServerFolder(String configured) {
        if (!configured.isEmpty()) {
            Path path = Paths.get(configured);
            return Files.isDirectory(path.resolve("logs")) ? path : null;
        }
        List<Path> roots = new ArrayList<>();
        for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (p.getFileName() != null && p.getFileName().toString().equals("instances")) roots.add(p);
        }
        roots.add(Paths.get("/home/amp/.ampdata/instances"));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> instances = Files.list(root)) {
                for (Path instance : (Iterable<Path>) instances::iterator) {
                    Path server = instance.resolve("Minecraft");
                    if (Files.isRegularFile(server.resolve("server.properties")) && Files.isDirectory(server.resolve("logs"))) {
                        return server;
                    }
                }
            } catch (IOException ignored) {
                // volgende proberen
            }
        }
        return null;
    }
}
