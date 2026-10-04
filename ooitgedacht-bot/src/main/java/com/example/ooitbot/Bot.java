package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.ErrorResponse;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * De bot zelf: Discord-verbinding, status, logs doorsturen en commando's.
 *
 * Draaiende onderdelen:
 *  - JDA (Discord), met eigen threads voor events;
 *  - 'scheduler': haalt elke paar seconden logs op bij de server en werkt
 *    elke minuut het statusbericht bij;
 *  - 'worker': voor Discord-commando's die op de server moeten wachten
 *    (RCON), zodat Discord-events niet blijven hangen.
 */
final class Bot extends ListenerAdapter implements ServerLink.Callbacks {

    final BotConfig config;
    final BotData data;
    final ServerLink link;
    final ExecutorService worker = Executors.newFixedThreadPool(2, daemon("ooitbot-worker"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(daemon("ooitbot-scheduler"));
    private final Rcon rcon;
    private JDA jda;

    private final String guildId;
    private final String logChannelId;
    private final String chatChannelId;
    private final String statusChannelId;
    private final String staffRoleId;
    private final String alertRoleId;
    private final String footerText;

    private final Set<String> pingOn = new HashSet<>();
    private final int pingCooldownSeconds;
    private final Map<String, Long> lastPing = new ConcurrentHashMap<>();
    private final String stopMessage;

    private final String presenceType;
    private final String presenceText;
    private final String presenceEmpty;
    private final String presenceSleeping;

    private final boolean statusMessageEnabled;
    private final int statusIntervalSeconds;
    private final StatusEmbed statusEmbed;

    private final int pollSeconds;
    private final int offlineCheckSeconds;

    private final GameCommands gameCommands;
    private final FunCommands funCommands;
    private final EmbedCommand embedCommand;
    private final GitHubFeed gitHubFeed;

    private volatile String lastPresence;
    private volatile boolean statusMessageSending;
    private long lastOfflineCheck = 0; // alleen op de scheduler-thread

    Bot(BotConfig config, BotData data) {
        this.config = config;
        this.data = data;

        guildId = config.id("bot.guild-id");
        logChannelId = config.id("bot.log-channel-id");
        chatChannelId = config.id("bot.chat-channel-id");
        statusChannelId = config.id("bot.status-channel-id");
        staffRoleId = config.id("bot.staff-role-id");
        alertRoleId = config.id("bot.alert-role-id");
        footerText = config.text("bot.embed-footer", "OoitGedacht SMP");

        for (String type : config.list("logs.ping-on")) pingOn.add(type.toLowerCase(Locale.ROOT));
        pingCooldownSeconds = config.integer("logs.ping-cooldown-seconds", 120);
        stopMessage = config.text("logs.stop-message", ":zzz: Server slaapt / gestopt");

        presenceType = config.text("status.type", "WATCHING").toUpperCase(Locale.ROOT);
        presenceText = config.text("status.text", "{online}/{max} spelers op OoitGedacht");
        presenceEmpty = config.text("status.text-empty", "een lege server");
        presenceSleeping = config.text("status.text-sleeping", "Server slaapt — join om te wekken");

        statusMessageEnabled = config.bool("status-message.enabled", true);
        statusIntervalSeconds = Math.max(15, config.integer("status-message.update-interval-seconds", 60));
        statusEmbed = new StatusEmbed(
                config.text("status-message.title", "OoitGedacht SMP — Serverstatus"),
                config.text("status-message.description", "Live overzicht van de server. Wordt elke minuut bijgewerkt."),
                config.id("status-message.server-address"),
                footerText,
                config.text("status-message.offline-text",
                        "**Status:** :zzz: Slaapt\nEr was niemand online. Join de server om hem wakker te maken (duurt even)."));

        pollSeconds = Math.max(1, config.integer("server.poll-interval-seconds", 2));
        offlineCheckSeconds = Math.max(2, config.integer("server.offline-check-seconds", 10));

        rcon = new Rcon(config.text("server.rcon-host", "127.0.0.1").trim(),
                config.integer("server.rcon-port", 25575),
                config.id("server.rcon-password"));
        link = new ServerLink(rcon, this);

        int topSize = Math.max(3, Math.min(25, config.integer("fun.leaderboard-size", 10)));
        gameCommands = new GameCommands(this, topSize);
        embedCommand = new EmbedCommand(this);
        if (config.bool("fun.enabled", true)) {
            ZoneId zone;
            try {
                zone = ZoneId.of(config.text("fun.timezone", "Europe/Amsterdam").trim());
            } catch (Exception e) {
                Log.warn("Onbekende tijdzone in fun.timezone, val terug op Europe/Amsterdam.");
                zone = ZoneId.of("Europe/Amsterdam");
            }
            funCommands = new FunCommands(this, zone, topSize);
        } else {
            funCommands = null;
        }

        if (config.bool("github.enabled", false)) {
            gitHubFeed = new GitHubFeed(this,
                    config.id("github.channel-id"),
                    config.id("github.repo"),
                    config.text("github.branch", "main").trim(),
                    config.id("github.token"),
                    Math.max(30, config.integer("github.check-interval-seconds", 120)));
        } else {
            gitHubFeed = null;
        }

        // Bij de allereerste start weten we nog niet sinds wanneer de server slaapt.
        if (data.number("sleeping-since", 0) == 0) data.putNumber("sleeping-since", System.currentTimeMillis());
    }

    void start() throws InterruptedException {
        if (config.id("server.rcon-password").isEmpty()) {
            Log.warn("Geen RCON-wachtwoord ingesteld (server.rcon-password); de bot kan de server dan niet bereiken.");
        }

        // "Light" = zo min mogelijk cache. Slash-commando's hebben geen speciale
        // intents nodig, dus ook geen extra vinkjes in het Developer Portal.
        jda = JDABuilder.createLight(config.id("bot.token"), Collections.emptyList())
                .setStatus(OnlineStatus.IDLE)
                .setActivity(activity(presenceSleeping))
                .addEventListeners(this)
                .build();
        jda.awaitReady();

        scheduler.scheduleWithFixedDelay(safe("logs ophalen", this::pollTick), 0, pollSeconds, TimeUnit.SECONDS);
        if (statusMessageEnabled) {
            scheduler.scheduleWithFixedDelay(safe("statusbericht", this::refreshStatusMessage),
                    5, statusIntervalSeconds, TimeUnit.SECONDS);
        }
        if (gitHubFeed != null) gitHubFeed.start();

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "ooitbot-shutdown"));
        Log.info("OoitGedacht Bot draait. Stoppen: de instance in AMP stoppen.");
    }

    private void shutdown() {
        Log.info("Bot wordt afgesloten...");
        scheduler.shutdownNow();
        worker.shutdownNow();
        if (gitHubFeed != null) gitHubFeed.shutdown();
        rcon.close();
        if (jda != null) {
            jda.shutdown();
            try {
                if (!jda.awaitShutdown(java.time.Duration.ofSeconds(5))) jda.shutdownNow();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Fouten in een herhalende taak opvangen; anders stopt de scheduler die taak voorgoed. */
    private static Runnable safe(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.warn("Fout bij " + name + ": " + t);
            }
        };
    }

    static java.util.concurrent.ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    // ---------- Server-verbinding ----------

    private void pollTick() {
        if (!link.isOnline()) {
            // Slapende server niet elke 2 seconden proberen.
            long now = System.currentTimeMillis();
            if (now - lastOfflineCheck < offlineCheckSeconds * 1000L) return;
            lastOfflineCheck = now;
        }
        link.poll();
        updatePresence();
    }

    @Override
    public void serverWokeUp() {
        updatePresence();
        refreshStatusMessage();
    }

    @Override
    public void serverWentToSleep() {
        Log.info("Minecraft-server slaapt of is gestopt.");
        data.putNumber("sleeping-since", System.currentTimeMillis());
        data.save();

        TextChannel channel = channel(logChannelId);
        if (channel != null) send(channel, embed(0x5865F2, stopMessage, null).build(), null);
        updatePresence();
        refreshStatusMessage();
    }

    @Override
    public void events(List<JsonNode> events) {
        for (JsonNode event : events) relay(event);
    }

    // ---------- Logs van de server doorsturen ----------

    private void relay(JsonNode ev) {
        String target = "chat".equals(ev.path("channel").asText()) && !chatChannelId.isEmpty() ? chatChannelId : logChannelId;
        TextChannel channel = channel(target);
        if (channel == null) return;

        MessageEmbed embed;
        try {
            EmbedBuilder eb = embed(ev.path("color").asInt(0x5865F2), textOrNull(ev, "title"), textOrNull(ev, "description"));
            eb.setTimestamp(Instant.ofEpochMilli(ev.path("time").asLong(System.currentTimeMillis())));
            String author = textOrNull(ev, "author");
            if (author != null) eb.setAuthor(cut(author, 256), null, headUrl(author));
            for (JsonNode f : ev.path("fields")) {
                eb.addField(cut(f.path("name").asText("-"), 256), cut(f.path("value").asText("-"), 1024),
                        f.path("inline").asBoolean(false));
            }
            if (eb.isEmpty()) return;
            embed = eb.build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            Log.warn("Ongeldige log van de server overgeslagen: " + e.getMessage());
            return;
        }

        String alertType = textOrNull(ev, "alert");
        boolean ping = alertType != null && !alertRoleId.isEmpty() && pingOn.contains(alertType)
                && !recentlyPinged(alertType + ":" + ev.path("subject").asText(""));
        send(channel, embed, ping ? alertRoleId : null);
    }

    private boolean recentlyPinged(String key) {
        long now = System.currentTimeMillis();
        Long last = lastPing.get(key);
        if (last != null && now - last < pingCooldownSeconds * 1000L) return true;
        lastPing.put(key, now);
        return false;
    }

    private void send(TextChannel channel, MessageEmbed embed, String pingRoleId) {
        if (pingRoleId == null) {
            channel.sendMessageEmbeds(embed)
                    .queue(null, err -> Log.warn("Kon bericht niet naar Discord sturen: " + err.getMessage()));
            return;
        }
        // Een ping werkt alleen in de gewone tekst, niet ín de embed. Alleen deze
        // ene rol toestaan, zodat er nooit per ongeluk @everyone o.i.d. afgaat.
        channel.sendMessage("<@&" + pingRoleId + ">")
                .setEmbeds(embed)
                .setAllowedMentions(Collections.emptyList())
                .mentionRoles(pingRoleId)
                .queue(null, err -> Log.warn("Kon bericht niet naar Discord sturen: " + err.getMessage()));
    }

    // ---------- Profielstatus ----------

    private void updatePresence() {
        if (jda == null) return;
        boolean online = link.isOnline();
        String text;
        if (!online) {
            text = presenceSleeping;
        } else if (link.players().isEmpty()) {
            text = presenceEmpty;
        } else {
            text = presenceText.replace("{online}", String.valueOf(link.players().size()))
                    .replace("{max}", String.valueOf(link.maxPlayers()));
        }

        String key = online + ":" + text;
        if (key.equals(lastPresence)) return;
        lastPresence = key;
        // Slapende server = bot op "afwezig" (het maantje).
        jda.getPresence().setPresence(online ? OnlineStatus.ONLINE : OnlineStatus.IDLE, activity(text));
    }

    private Activity activity(String text) {
        switch (presenceType) {
            case "PLAYING": return Activity.playing(text);
            case "CUSTOM": return Activity.customStatus(text);
            default: return Activity.watching(text);
        }
    }

    // ---------- Statusbericht ----------

    private void refreshStatusMessage() {
        if (!statusMessageEnabled || jda == null) return;
        TextChannel channel = channel(statusChannelId);
        if (channel == null) return;

        JsonNode status = null;
        if (link.isOnline()) {
            try {
                status = link.rpc(link.request("status"));
                JsonNode timeline = status.get("timeline");
                if (timeline != null && !timeline.equals(data.node("last-timeline"))) {
                    data.putNode("last-timeline", timeline);
                    data.save();
                }
            } catch (IOException e) {
                // Server reageert even niet; de volgende poll merkt vanzelf of hij slaapt.
                return;
            }
        }

        JsonNode timeline = status != null ? status.get("timeline") : data.node("last-timeline");
        MessageEmbed embed = statusEmbed.build(status, timeline, data.number("sleeping-since", System.currentTimeMillis()));

        String messageId = statusChannelId.equals(data.text("status-channel-id")) ? data.text("status-message-id") : null;
        if (messageId == null) {
            if (statusMessageSending) return;
            statusMessageSending = true;
            channel.sendMessageEmbeds(embed).queue(
                    msg -> {
                        statusMessageSending = false;
                        data.putText("status-channel-id", statusChannelId);
                        data.putText("status-message-id", msg.getId());
                        data.save();
                    },
                    err -> {
                        statusMessageSending = false;
                        Log.warn("Kon statusbericht niet plaatsen: " + err.getMessage());
                    });
        } else {
            channel.editMessageEmbedsById(messageId, embed).queue(null, err -> {
                if (err instanceof ErrorResponseException
                        && ((ErrorResponseException) err).getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                    // Bericht is verwijderd: bij de volgende update komt er een nieuw.
                    data.putText("status-message-id", null);
                    data.save();
                } else {
                    Log.warn("Kon statusbericht niet bijwerken: " + err.getMessage());
                }
            });
        }
    }

    // ---------- Discord-events ----------

    @Override
    public void onReady(ReadyEvent event) {
        Log.info("Ingelogd op Discord als " + event.getJDA().getSelfUser().getName() + ".");

        Guild guild = guildId.isEmpty() ? null : event.getJDA().getGuildById(guildId);
        if (guild == null) {
            Log.warn("Discord-server met guild-id '" + guildId + "' niet gevonden. "
                    + "Klopt het ID en is de bot uitgenodigd op die server?");
        } else {
            registerCommands(guild);
        }

        warnIfMissing("Log-kanaal", logChannelId);
        warnIfMissing("Chat-kanaal", chatChannelId);
        warnIfMissing("Status-kanaal", statusChannelId);
        if (gitHubFeed != null) {
            if (gitHubFeed.channelId().isEmpty()) Log.warn("github.enabled staat aan, maar github.channel-id is leeg.");
            else warnIfMissing("GitHub-kanaal", gitHubFeed.channelId());
        }
    }

    private void warnIfMissing(String label, String channelId) {
        if (!channelId.isEmpty() && channel(channelId) == null) {
            Log.warn(label + " met ID '" + channelId + "' niet gevonden, of de bot mag er niet in.");
        }
    }

    private void registerCommands(Guild guild) {
        // Zonder extra rol: alleen admins (en de eigenaar) zien de beheer-commando's.
        // Met een staff-rol: iedereen ziet ze, maar isStaff() blokkeert de rest.
        DefaultMemberPermissions staffOnly = staffRoleId.isEmpty()
                ? DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)
                : DefaultMemberPermissions.ENABLED;

        List<SlashCommandData> all = new ArrayList<>(GameCommands.definitions(staffOnly));
        all.add(EmbedCommand.command(staffOnly));
        if (funCommands != null) all.addAll(FunCommands.commands());

        guild.updateCommands().addCommands(all).queue(
                ok -> Log.info("Discord-commando's geregistreerd op " + guild.getName() + "."),
                err -> Log.warn("Kon Discord-commando's niet registreren: " + err.getMessage()));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        if (gameCommands.handle(event)) return;
        if (funCommands != null && funCommands.handle(event)) return;

        if (event.getName().equals("embed")) {
            if (!isStaff(event.getMember())) {
                event.reply(":x: Alleen de eigenaar en beheerders mogen dit commando gebruiken.").setEphemeral(true).queue();
                return;
            }
            embedCommand.handle(event);
        }
    }

    @Override
    public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
        if (!event.getFocusedOption().getName().equals("speler")) return;

        String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String name : link.players()) {
            if (name.toLowerCase(Locale.ROOT).startsWith(typed)) matches.add(name);
            if (matches.size() == 25) break; // Discord-limiet
        }
        event.replyChoiceStrings(matches).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        embedCommand.handleModal(event);
    }

    // ---------- Hulpjes voor de commando-klassen ----------

    boolean isStaff(Member member) {
        if (member == null) return false;
        if (member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR)) return true;
        return !staffRoleId.isEmpty()
                && member.getRoles().stream().anyMatch(role -> role.getId().equals(staffRoleId));
    }

    /** Basis-embed met gekleurde rand, tijdstip en voettekst. title of description mag null zijn. */
    EmbedBuilder embed(int color, String title, String description) {
        EmbedBuilder eb = new EmbedBuilder().setColor(color).setTimestamp(Instant.now());
        if (title != null && !title.isEmpty()) eb.setTitle(cut(title, 256));
        if (description != null && !description.isEmpty()) eb.setDescription(cut(description, 4096));
        if (!footerText.isEmpty()) eb.setFooter(footerText);
        return eb;
    }

    TextChannel channel(String channelId) {
        if (jda == null || channelId.isEmpty()) return null;
        try {
            return jda.getTextChannelById(channelId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Minecraft-hoofd bij een geldige spelersnaam (niet bij bv. "Boat (vehicle of Steve)"). */
    private static String headUrl(String name) {
        return name.matches("[A-Za-z0-9_]{1,16}") ? "https://mc-heads.net/avatar/" + name + "/64" : null;
    }

    private static String textOrNull(JsonNode node, String key) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? null : value.asText();
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }
}
