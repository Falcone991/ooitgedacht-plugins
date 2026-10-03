package com.example.discordbot;

import io.papermc.paper.ban.BanListType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
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
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class DiscordBot extends JavaPlugin implements Listener {

    // Kleur van de rand per soort log.
    private static final int COLOR_START = 0x57F287;
    private static final int COLOR_STOP = 0xED4245;
    private static final int COLOR_JOIN = 0x57F287;
    private static final int COLOR_LEAVE = 0x95A5A6;
    private static final int COLOR_DEATH = 0x992D22;
    private static final int COLOR_CHAT = 0x5865F2;
    private static final int COLOR_REPORT = 0xE67E22;
    private static final int COLOR_DISCORD_ACTION = 0x9B59B6;
    private static final int COLOR_GAMEMODE = 0xFEE75C;
    private static final int COLOR_CREATIVE = 0x1ABC9C;
    private static final int COLOR_MOVE = 0xE67E22;
    private static final int COLOR_COMMAND = 0x607D8B;

    private JDA jda;
    private MoveWarningWatcher moveWatcher;
    private StatusBoard statusBoard;
    private FunCommands funCommands;
    private EmbedCommand embedCommand;

    // Instellingen (uit config.yml)
    private String token;
    private String guildId;
    private String logChannelId;
    private String chatChannelId;
    private String statusChannelId;
    private String staffRoleId;
    private String footerText;

    private String statusType;
    private String statusText;
    private String statusTextEmpty;

    private boolean statusMessageEnabled;
    private int statusMessageIntervalSeconds;

    private boolean logServerStatus;
    private boolean logJoin;
    private boolean logLeave;
    private boolean logDeath;
    private boolean logChat;
    private boolean logReports;
    private boolean logDiscordActions;
    private boolean logGamemode;
    private boolean logCreativeItems;
    private int creativeItemCooldownSeconds;
    private boolean logMoveWarnings;
    private boolean logMovedWrongly;
    private int moveWarningCooldownSeconds;
    private boolean logCommands;
    private Set<String> watchedCommands;

    private String alertRoleId;
    private Set<String> pingOn;
    private int pingCooldownSeconds;

    private String msgPrefix;
    private String kickMessage;
    private String banMessage;
    private String defaultReason;

    private String discordInviteLink;
    private String discordCommandMessage;
    private String discordCommandHover;

    // Persistente data: ID van het statusbericht, zodat we na een herstart
    // hetzelfde bericht blijven bijwerken i.p.v. telkens een nieuw te plaatsen.
    private File dataFile;
    private FileConfiguration data;
    private volatile String statusMessageId;
    private volatile boolean statusMessageSending = false;

    // Namen van de online spelers. Wordt op de hoofdthread bijgewerkt en door
    // Discord-threads gelezen (autocomplete, /spelers), vandaar volatile.
    private volatile List<String> onlineNames = Collections.emptyList();
    // Laatst ingestelde status, om Discord niet onnodig te spammen met updates.
    private String lastStatus = null;

    // Anti-spam: wanneer iets voor het laatst gelogd is (sleutel -> tijdstip in ms).
    // Concurrent, want de console-meldingen komen van allerlei threads binnen.
    private final Map<String, Long> lastMoveWarning = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCreativeItem = new ConcurrentHashMap<>();
    private final Map<String, Long> lastPing = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Nieuwe instellingen uit een update aanvullen in een bestaande config.yml,
        // zonder iets te overschrijven wat je zelf al hebt ingevuld.
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadSettings();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("discord").setExecutor(this);

        if (token.isEmpty()) {
            getLogger().warning("Geen bot-token ingesteld in config.yml, de Discord-bot start niet.");
            return;
        }

        try {
            // "Light" = zo min mogelijk cache. Slash-commando's hebben geen
            // speciale intents nodig, dus ook geen extra vinkjes in het Developer Portal.
            jda = JDABuilder.createLight(token, Collections.emptyList())
                    .addEventListeners(new DiscordListener())
                    .build();
        } catch (Exception e) {
            getLogger().severe("Kon de Discord-bot niet starten (klopt de token?): " + e.getMessage());
            jda = null;
            return;
        }

        if (logMoveWarnings) {
            try {
                moveWatcher = new MoveWarningWatcher(this::onMoveWarning, logMovedWrongly);
                moveWatcher.install();
            } catch (Throwable t) {
                getLogger().warning("Kon 'moved too quickly'-meldingen niet meelezen: " + t);
                moveWatcher = null;
            }
        }

        refreshStatus();
        // Elke minuut de status opnieuw zetten, voor het geval er een update gemist is.
        Bukkit.getScheduler().runTaskTimer(this, this::refreshStatus, 20L * 60, 20L * 60);

        if (statusMessageEnabled) {
            long interval = 20L * statusMessageIntervalSeconds;
            Bukkit.getScheduler().runTaskTimer(this, () -> updateStatusMessage(true, false), interval, interval);
        }

        getLogger().info("DiscordBot wordt gestart...");
    }

    @Override
    public void onDisable() {
        if (moveWatcher != null) {
            try {
                moveWatcher.uninstall();
            } catch (Throwable ignored) {
            }
            moveWatcher = null;
        }

        if (jda == null) return;

        // Hieronder .complete() i.p.v. .queue(): wachten tot het verstuurd is,
        // anders sluit de bot af voordat de berichten weg zijn.
        if (logServerStatus) {
            TextChannel channel = getChannel(logChannelId);
            if (channel != null) {
                try {
                    channel.sendMessageEmbeds(embed(COLOR_STOP, ":red_circle: Server gestopt", null).build()).complete();
                } catch (Exception ignored) {
                }
            }
        }
        updateStatusMessage(false, true);

        jda.shutdown();
        try {
            if (!jda.awaitShutdown(Duration.ofSeconds(5))) {
                jda.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        jda = null;
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        token = c.getString("bot.token", "").trim();
        guildId = c.getString("bot.guild-id", "").trim();
        logChannelId = c.getString("bot.log-channel-id", "").trim();
        chatChannelId = c.getString("bot.chat-channel-id", "").trim();
        statusChannelId = c.getString("bot.status-channel-id", "").trim();
        staffRoleId = c.getString("bot.staff-role-id", "").trim();
        footerText = c.getString("bot.embed-footer", "OoitGedacht SMP");

        statusType = c.getString("status.type", "WATCHING").toUpperCase(Locale.ROOT);
        statusText = c.getString("status.text", "{online}/{max} spelers op OoitGedacht");
        statusTextEmpty = c.getString("status.text-empty", "een lege server");

        statusMessageEnabled = c.getBoolean("status-message.enabled", true);
        statusMessageIntervalSeconds = Math.max(15, c.getInt("status-message.update-interval-seconds", 60));
        statusBoard = new StatusBoard(
                c.getString("status-message.title", "OoitGedacht SMP — Serverstatus"),
                c.getString("status-message.description", "Live overzicht van de server. Wordt elke minuut bijgewerkt."),
                c.getString("status-message.server-address", "").trim(),
                footerText);

        logServerStatus = c.getBoolean("logs.server-start-stop", true);
        logJoin = c.getBoolean("logs.join", true);
        logLeave = c.getBoolean("logs.leave", true);
        logDeath = c.getBoolean("logs.death", true);
        logChat = c.getBoolean("logs.chat", true);
        logReports = c.getBoolean("logs.reports", true);
        logDiscordActions = c.getBoolean("logs.discord-actions", true);
        logGamemode = c.getBoolean("logs.gamemode-changes", true);
        logCreativeItems = c.getBoolean("logs.creative-items", true);
        creativeItemCooldownSeconds = c.getInt("logs.creative-items-cooldown-seconds", 30);
        logMoveWarnings = c.getBoolean("logs.moved-too-quickly", true);
        logMovedWrongly = c.getBoolean("logs.moved-wrongly", false);
        moveWarningCooldownSeconds = c.getInt("logs.moved-too-quickly-cooldown-seconds", 30);
        logCommands = c.getBoolean("logs.commands", true);

        watchedCommands = new HashSet<>();
        for (String cmd : c.getStringList("logs.watched-commands")) {
            watchedCommands.add(cmd.toLowerCase(Locale.ROOT).replace("/", "").trim());
        }

        alertRoleId = c.getString("bot.alert-role-id", "").trim();
        pingOn = new HashSet<>();
        for (String type : c.getStringList("logs.ping-on")) {
            pingOn.add(type.toLowerCase(Locale.ROOT).trim());
        }
        pingCooldownSeconds = c.getInt("logs.ping-cooldown-seconds", 120);

        msgPrefix = c.getString("messages.msg-prefix", "&9[Discord] &b{discord}&7: &r");
        kickMessage = c.getString("messages.kick-message", "&cJe bent gekickt door beheer.\n&7Reden: &f{reason}");
        banMessage = c.getString("messages.ban-message", "&cJe bent verbannen van de server.\n&7Reden: &f{reason}");
        defaultReason = c.getString("messages.default-reason", "Geen reden opgegeven");

        embedCommand = new EmbedCommand(this);
        if (c.getBoolean("fun.enabled", true)) {
            ZoneId zone;
            try {
                zone = ZoneId.of(c.getString("fun.timezone", "Europe/Amsterdam"));
            } catch (Exception e) {
                getLogger().warning("Onbekende tijdzone in fun.timezone, val terug op Europe/Amsterdam.");
                zone = ZoneId.of("Europe/Amsterdam");
            }
            funCommands = new FunCommands(this, zone, c.getInt("fun.leaderboard-size", 10));
        } else {
            funCommands = null;
        }

        discordInviteLink = c.getString("discord-command.invite-link", "").trim();
        discordCommandMessage = c.getString("discord-command.message", "&9&lDiscord: &b&n{link}");
        discordCommandHover = c.getString("discord-command.hover", "&7Klik om onze Discord te openen");
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        // Alleen het opgeslagen bericht hergebruiken als het nog in hetzelfde kanaal staat.
        if (statusChannelId.equals(data.getString("status-channel-id", ""))) {
            statusMessageId = data.getString("status-message-id");
        }
    }

    /** Gedeelde data.yml (statusbericht, streaks). Alleen op de hoofdthread gebruiken. */
    FileConfiguration data() {
        return data;
    }

    void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Kon data.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- In-game /discord ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("discord")) return false;

        if (discordInviteLink.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Er is nog geen Discord-link ingesteld.");
            return true;
        }

        sender.sendMessage(legacy(discordCommandMessage.replace("{link}", discordInviteLink))
                .clickEvent(ClickEvent.openUrl(discordInviteLink))
                .hoverEvent(HoverEvent.showText(legacy(discordCommandHover))));
        return true;
    }

    // ---------- Status in het bot-profiel ----------

    /** Moet op de hoofdthread draaien (leest de spelerslijst). */
    private void refreshStatus() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        onlineNames = Collections.unmodifiableList(names);

        if (jda == null) return;

        String text = names.isEmpty()
                ? statusTextEmpty
                : statusText.replace("{online}", String.valueOf(names.size()))
                        .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()));
        if (text.equals(lastStatus)) return;
        lastStatus = text;

        Activity activity;
        switch (statusType) {
            case "PLAYING":
                activity = Activity.playing(text);
                break;
            case "CUSTOM":
                activity = Activity.customStatus(text);
                break;
            default:
                activity = Activity.watching(text);
                break;
        }
        jda.getPresence().setActivity(activity);
    }

    // ---------- Statusbericht in het statuskanaal ----------

    /**
     * Werkt het vaste statusbericht bij, of plaatst het als het nog niet bestaat
     * (of door iemand verwijderd is). Moet op de hoofdthread draaien.
     *
     * @param blocking true bij het afsluiten: wachten tot de update verstuurd is.
     */
    private void updateStatusMessage(boolean online, boolean blocking) {
        if (!statusMessageEnabled) return;
        TextChannel channel = getChannel(statusChannelId);
        if (channel == null) return;

        MessageEmbed embed;
        try {
            embed = statusBoard.build(online);
        } catch (Exception e) {
            getLogger().warning("Kon statusbericht niet opbouwen: " + e);
            return;
        }

        String messageId = statusMessageId;
        try {
            if (messageId == null) {
                // Bij afsluiten geen nieuw bericht plaatsen, en nooit twee tegelijk.
                if (blocking || statusMessageSending) return;
                statusMessageSending = true;
                channel.sendMessageEmbeds(embed).queue(
                        msg -> {
                            statusMessageSending = false;
                            statusMessageId = msg.getId();
                            onMainThread(() -> {
                                data.set("status-channel-id", statusChannelId);
                                data.set("status-message-id", msg.getId());
                                saveData();
                            });
                        },
                        err -> {
                            statusMessageSending = false;
                            getLogger().warning("Kon statusbericht niet plaatsen: " + err.getMessage());
                        });
            } else if (blocking) {
                channel.editMessageEmbedsById(messageId, embed).complete();
            } else {
                channel.editMessageEmbedsById(messageId, embed).queue(null, err -> {
                    if (err instanceof ErrorResponseException
                            && ((ErrorResponseException) err).getErrorResponse() == ErrorResponse.UNKNOWN_MESSAGE) {
                        // Bericht is verwijderd: bij de volgende update komt er een nieuw.
                        statusMessageId = null;
                    } else {
                        getLogger().warning("Kon statusbericht niet bijwerken: " + err.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            getLogger().warning("Kon statusbericht niet bijwerken: " + e.getMessage());
        }
    }

    // ---------- Logs ----------

    private TextChannel getChannel(String channelId) {
        if (jda == null || channelId.isEmpty()) return null;
        try {
            return jda.getTextChannelById(channelId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Basis-embed met gekleurde rand en tijdstip. title of description mag null zijn. */
    EmbedBuilder embed(int color, String title, String description) {
        EmbedBuilder eb = new EmbedBuilder().setColor(color).setTimestamp(Instant.now());
        if (title != null) eb.setTitle(cut(title, 256));
        if (description != null) eb.setDescription(cut(description, 4096));
        if (!footerText.isEmpty()) eb.setFooter(footerText);
        return eb;
    }

    /** Zelfde, met de naam + het Minecraft-hoofd van de speler bovenaan. */
    private EmbedBuilder playerEmbed(String playerName, int color, String title, String description) {
        EmbedBuilder eb = embed(color, title, description);
        // Hoofdje alleen bij een geldige Minecraft-naam (bv. niet bij "Boat (vehicle of Steve)").
        String icon = playerName.matches("[A-Za-z0-9_]{1,16}")
                ? "https://mc-heads.net/avatar/" + playerName + "/64"
                : null;
        return eb.setAuthor(cut(playerName, 256), null, icon);
    }

    /** Naar het log-kanaal. Mag vanaf elke thread; JDA verstuurt zelf op de achtergrond. */
    private void log(EmbedBuilder embed) {
        send(logChannelId, embed, null);
    }

    /**
     * Belangrijke log: zelfde als log(), maar pingt de alert-rol als dit soort log
     * in logs.ping-on staat. Per soort + onderwerp (meestal de speler) hooguit
     * één ping per ping-cooldown, zodat een laggende speler niet iedereen spamt.
     */
    private void alert(String type, String subject, EmbedBuilder embed) {
        boolean ping = !alertRoleId.isEmpty() && pingOn.contains(type)
                && !recentlyLogged(lastPing, type + ":" + subject, pingCooldownSeconds);
        send(logChannelId, embed, ping ? alertRoleId : null);
    }

    /** Naar het chat-kanaal, of het log-kanaal als er geen apart chat-kanaal is ingesteld. */
    private void chat(EmbedBuilder embed) {
        send(chatChannelId.isEmpty() ? logChannelId : chatChannelId, embed, null);
    }

    private void send(String channelId, EmbedBuilder embed, String pingRoleId) {
        TextChannel channel = getChannel(channelId);
        if (channel == null) return;

        if (pingRoleId == null) {
            channel.sendMessageEmbeds(embed.build())
                    .queue(null, err -> getLogger().warning("Kon bericht niet naar Discord sturen: " + err.getMessage()));
            return;
        }

        // Een ping werkt alleen in de gewone tekst, niet ín de embed. Alleen deze
        // ene rol toestaan, zodat er nooit per ongeluk @everyone o.i.d. afgaat.
        channel.sendMessage("<@&" + pingRoleId + ">")
                .setEmbeds(embed.build())
                .setAllowedMentions(Collections.emptyList())
                .mentionRoles(pingRoleId)
                .queue(null, err -> getLogger().warning("Kon bericht niet naar Discord sturen: " + err.getMessage()));
    }

    /** true als 'key' het afgelopen 'seconds' al gelogd is; zo niet, wordt het nu als gelogd gemarkeerd. */
    private static boolean recentlyLogged(Map<String, Long> map, String key, int seconds) {
        long now = System.currentTimeMillis();
        Long last = map.get(key);
        if (last != null && now - last < seconds * 1000L) return true;
        map.put(key, now);
        return false;
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
    }

    /** Veilig als embed-veld: markdown ge-escaped, niet leeg, max 1024 tekens. */
    private static String field(String text) {
        if (text == null || text.isEmpty()) return "-";
        return cut(md(text), 1024);
    }

    static String md(String text) {
        return MarkdownSanitizer.escape(text);
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    private static String prettyName(String enumName) {
        return enumName.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String codeBlock(String text) {
        return "`" + text.replace("`", "'") + "`";
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(this, this::refreshStatus);
        if (logJoin) {
            String name = event.getPlayer().getName();
            log(playerEmbed(name, COLOR_JOIN, ":inbox_tray: Ingelogd",
                    "**" + md(name) + "** is de server binnengekomen."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Een tick later, want tijdens dit event telt de speler nog mee als online.
        Bukkit.getScheduler().runTask(this, this::refreshStatus);
        if (logLeave) {
            String name = event.getPlayer().getName();
            log(playerEmbed(name, COLOR_LEAVE, ":outbox_tray: Uitgelogd",
                    "**" + md(name) + "** heeft de server verlaten."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!logDeath) return;
        Component deathMessage = event.deathMessage();
        String name = event.getEntity().getName();
        String text = deathMessage != null ? plain(deathMessage) : name + " is doodgegaan";
        log(playerEmbed(name, COLOR_DEATH, ":skull: Dood", md(text)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!logChat) return;
        String message = plain(event.message());
        if (message.isBlank()) return;
        chat(playerEmbed(event.getPlayer().getName(), COLOR_CHAT, null, md(message)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        if (!logGamemode) return;

        String cause;
        switch (event.getCause()) {
            case COMMAND: cause = "Commando"; break;
            case PLUGIN: cause = "Plugin"; break;
            case HARDCORE_DEATH: cause = "Hardcore-dood"; break;
            case DEFAULT_GAMEMODE: cause = "Standaard-gamemode van de server"; break;
            default: cause = "Onbekend"; break;
        }

        alert("gamemode-changes", event.getPlayer().getName(),
                playerEmbed(event.getPlayer().getName(), COLOR_GAMEMODE, ":video_game: Gamemode gewijzigd",
                prettyName(event.getPlayer().getGameMode().name()) + " → **"
                        + prettyName(event.getNewGameMode().name()) + "**")
                .addField("Oorzaak", cause, true));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreativeItem(InventoryCreativeEvent event) {
        if (!logCreativeItems) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        ItemStack item = event.getCursor();
        if (item == null || item.getType().isAir()) return;

        // Dit event gaat ook af bij gewoon items verschuiven in creative. Daarom
        // per speler + itemsoort maar één keer per X seconden loggen.
        Player player = (Player) event.getWhoClicked();
        String key = player.getUniqueId() + ":" + item.getType();
        if (recentlyLogged(lastCreativeItem, key, creativeItemCooldownSeconds)) return;

        alert("creative-items", player.getName(), playerEmbed(player.getName(), COLOR_CREATIVE, ":package: Item uit creative",
                "**" + item.getAmount() + "x " + prettyName(item.getType().name()) + "**"));
    }

    private void onMoveWarning(String consoleLine) {
        // Vanilla-formaat: "<naam> moved too quickly! x,y,z" (of "... moved wrongly!").
        int end = consoleLine.indexOf(" moved ");
        String who = end > 0 ? consoleLine.substring(0, end) : "?";
        if (recentlyLogged(lastMoveWarning, who, moveWarningCooldownSeconds)) return;

        boolean wrongly = consoleLine.contains("moved wrongly!");
        String title = wrongly ? ":warning: Ongeldige beweging" : ":warning: Bewoog te snel";
        String explanation = wrongly
                ? "Komt vaak door lag of een glitch."
                : "Kan lag zijn, maar ook een fly/speed-hack. Houd deze speler in de gaten.";

        alert(wrongly ? "moved-wrongly" : "moved-too-quickly", who, playerEmbed(who, COLOR_MOVE, title, explanation)
                .addField("Console", codeBlock(cut(consoleLine, 1000)), false));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().substring(1);
        String[] parts = command.split(" ", 3);
        String label = stripNamespace(parts[0]);
        String name = event.getPlayer().getName();

        // "/report <speler> <reden>" van de StaffTools-plugin meelezen.
        if (logReports && label.equals("report") && parts.length == 3) {
            alert("reports", name, playerEmbed(name, COLOR_REPORT, ":triangular_flag_on_post: Nieuwe report", null)
                    .addField("Gemeld door", field(name), true)
                    .addField("Speler", field(parts[1]), true)
                    .addField("Reden", field(parts[2]), false));
            return;
        }

        if (logCommands && watchedCommands.contains(label)) {
            alert("commands", name, playerEmbed(name, COLOR_COMMAND, ":keyboard: Commando gebruikt", codeBlock("/" + cut(command, 1500))));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!logCommands) return;
        // Command blocks niet loggen, die kunnen elke tick afgaan.
        if (event.getSender() instanceof BlockCommandSender) return;

        String command = event.getCommand().startsWith("/") ? event.getCommand().substring(1) : event.getCommand();
        String label = stripNamespace(command.split(" ", 2)[0]);
        if (watchedCommands.contains(label)) {
            alert("commands", event.getSender().getName(),
                    embed(COLOR_COMMAND, ":keyboard: Commando gebruikt (" + event.getSender().getName() + ")",
                    codeBlock("/" + cut(command, 1500))));
        }
    }

    /** "minecraft:give" -> "give". */
    private static String stripNamespace(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon >= 0 ? lower.substring(colon + 1) : lower;
    }

    // ---------- Discord-kant ----------

    boolean isDiscordStaff(Member member) {
        if (member == null) return false;
        if (member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR)) return true;
        return !staffRoleId.isEmpty()
                && member.getRoles().stream().anyMatch(role -> role.getId().equals(staffRoleId));
    }

    /** Voert iets uit op de Minecraft-hoofdthread; Bukkit-API mag niet vanaf Discord-threads. */
    void onMainThread(Runnable task) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, task);
    }

    private class DiscordListener extends ListenerAdapter {

        @Override
        public void onReady(ReadyEvent event) {
            getLogger().info("Ingelogd op Discord als " + event.getJDA().getSelfUser().getName() + ".");

            Guild guild = guildId.isEmpty() ? null : event.getJDA().getGuildById(guildId);
            if (guild == null) {
                getLogger().warning("Discord-server met guild-id '" + guildId + "' niet gevonden. "
                        + "Klopt het ID en is de bot uitgenodigd op die server?");
            } else {
                registerCommands(guild);
            }

            warnIfMissing("Log-kanaal", logChannelId);
            warnIfMissing("Chat-kanaal", chatChannelId);
            warnIfMissing("Status-kanaal", statusChannelId);

            if (logServerStatus) {
                log(embed(COLOR_START, ":green_circle: Server gestart", "De server is online en klaar om te spelen."));
            }
            onMainThread(() -> updateStatusMessage(true, false));
        }

        private void warnIfMissing(String label, String channelId) {
            if (!channelId.isEmpty() && getChannel(channelId) == null) {
                getLogger().warning(label + " met ID '" + channelId + "' niet gevonden, of de bot mag er niet in.");
            }
        }

        private void registerCommands(Guild guild) {
            // Zonder extra rol: alleen admins (en de eigenaar) zien de beheer-commando's.
            // Met een staff-rol: iedereen ziet ze, maar isDiscordStaff() blokkeert de rest.
            DefaultMemberPermissions staffOnly = staffRoleId.isEmpty()
                    ? DefaultMemberPermissions.enabledFor(Permission.ADMINISTRATOR)
                    : DefaultMemberPermissions.ENABLED;

            SlashCommandData spelers = Commands.slash("spelers", "Bekijk wie er nu online is op de Minecraft-server.");
            SlashCommandData msg = Commands.slash("msg", "Stuur een speler in-game een bericht als beheer.")
                    .addOption(OptionType.STRING, "speler", "Naam van de speler (moet online zijn)", true, true)
                    .addOption(OptionType.STRING, "bericht", "Het bericht", true)
                    .setDefaultPermissions(staffOnly);
            SlashCommandData kick = Commands.slash("kick", "Kick een speler van de Minecraft-server.")
                    .addOption(OptionType.STRING, "speler", "Naam van de speler (moet online zijn)", true, true)
                    .addOption(OptionType.STRING, "reden", "Waarom?", false)
                    .setDefaultPermissions(staffOnly);
            SlashCommandData ban = Commands.slash("ban", "Verban een speler van de Minecraft-server.")
                    .addOption(OptionType.STRING, "speler", "Naam van de speler", true, true)
                    .addOption(OptionType.STRING, "reden", "Waarom?", false)
                    .setDefaultPermissions(staffOnly);
            SlashCommandData unban = Commands.slash("unban", "Hef een ban van een speler op.")
                    .addOption(OptionType.STRING, "speler", "Naam van de speler", true)
                    .setDefaultPermissions(staffOnly);

            List<SlashCommandData> all = new ArrayList<>(List.of(spelers, msg, kick, ban, unban,
                    EmbedCommand.command(staffOnly)));
            if (funCommands != null) all.addAll(FunCommands.commands());

            guild.updateCommands().addCommands(all).queue(
                    ok -> getLogger().info("Discord-commando's geregistreerd op " + guild.getName() + "."),
                    err -> getLogger().warning("Kon Discord-commando's niet registreren: " + err.getMessage()));
        }

        @Override
        public void onCommandAutoCompleteInteraction(CommandAutoCompleteInteractionEvent event) {
            if (!event.getFocusedOption().getName().equals("speler")) return;

            String typed = event.getFocusedOption().getValue().toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String name : onlineNames) {
                if (name.toLowerCase(Locale.ROOT).startsWith(typed)) matches.add(name);
                if (matches.size() == 25) break; // Discord-limiet
            }
            event.replyChoiceStrings(matches).queue();
        }

        @Override
        public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
            String name = event.getName();
            if (name.equals("spelers")) {
                handleSpelers(event);
                return;
            }
            if (funCommands != null && funCommands.handle(event)) return;

            boolean staffCommand = name.equals("msg") || name.equals("kick") || name.equals("ban")
                    || name.equals("unban") || name.equals("embed");
            if (!staffCommand) return;

            if (!isDiscordStaff(event.getMember())) {
                event.reply(":x: Alleen de eigenaar en beheerders mogen dit commando gebruiken.").setEphemeral(true).queue();
                return;
            }

            if (name.equals("embed")) {
                embedCommand.handle(event);
                return;
            }

            // Antwoord uitstellen: het echte werk gebeurt straks op de Minecraft-thread.
            // Ephemeral = alleen jij ziet het antwoord.
            event.deferReply(true).queue();

            String target = event.getOption("speler", OptionMapping::getAsString);
            String by = event.getMember().getEffectiveName();
            Consumer<String> reply = text -> event.getHook().editOriginal(text).queue();

            switch (name) {
                case "msg":
                    handleMsg(target, event.getOption("bericht", OptionMapping::getAsString), by, reply);
                    break;
                case "kick":
                    handleKick(target, event.getOption("reden", defaultReason, OptionMapping::getAsString), by, reply);
                    break;
                case "ban":
                    handleBan(target, event.getOption("reden", defaultReason, OptionMapping::getAsString), by, reply);
                    break;
                case "unban":
                    handleUnban(target, by, reply);
                    break;
            }
        }

        @Override
        public void onModalInteraction(ModalInteractionEvent event) {
            embedCommand.handleModal(event);
        }

        private void handleSpelers(SlashCommandInteractionEvent event) {
            List<String> names = onlineNames;
            EmbedBuilder eb = embed(COLOR_CHAT, ":busts_in_silhouette: Spelers online: "
                    + names.size() + "/" + Bukkit.getMaxPlayers(), null);
            if (names.isEmpty()) {
                eb.setDescription("Er is nu niemand online.");
            } else {
                List<String> escaped = new ArrayList<>();
                for (String n : names) escaped.add(md(n));
                eb.setDescription(cut(String.join(", ", escaped), 4096));
            }
            event.replyEmbeds(eb.build()).queue();
        }
    }

    // ---------- Beheer-acties (draaien op de Minecraft-hoofdthread) ----------

    private void notifyIngameStaff(String text, Player except) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(except)) continue;
            if (online.hasPermission("discordbot.seemsg")) {
                online.sendMessage(ChatColor.GRAY + text);
            }
        }
    }

    private void logDiscordAction(String title, String by, String target, String detailName, String detail) {
        if (!logDiscordActions) return;
        EmbedBuilder eb = playerEmbed(target, COLOR_DISCORD_ACTION, title, null)
                .addField("Door (Discord)", field(by), true)
                .addField("Speler", field(target), true);
        if (detailName != null) eb.addField(detailName, field(detail), false);
        log(eb);
    }

    private void handleMsg(String targetName, String message, String by, Consumer<String> reply) {
        onMainThread(() -> {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                reply.accept(":x: **" + md(targetName) + "** is niet online.");
                return;
            }

            target.sendMessage(legacy(msgPrefix.replace("{discord}", by)).append(Component.text(message)));
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
            notifyIngameStaff("[Discord] " + by + " -> " + target.getName() + ": " + message, target);

            reply.accept(":white_check_mark: Bericht verstuurd naar **" + md(target.getName()) + "**.");
            logDiscordAction(":speech_left: Bericht via Discord", by, target.getName(), "Bericht", message);
        });
    }

    private void handleKick(String targetName, String reason, String by, Consumer<String> reply) {
        onMainThread(() -> {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                reply.accept(":x: **" + md(targetName) + "** is niet online.");
                return;
            }

            target.kick(legacy(kickMessage.replace("{reason}", reason)));
            notifyIngameStaff("[Discord] " + by + " kickte " + target.getName() + ": " + reason, null);

            reply.accept(":white_check_mark: **" + md(target.getName()) + "** is gekickt.");
            logDiscordAction(":boot: Gekickt via Discord", by, target.getName(), "Reden", reason);
        });
    }

    private void handleBan(String targetName, String reason, String by, Consumer<String> reply) {
        onMainThread(() -> {
            // Alleen spelers die ooit op de server zijn geweest; zo hoeven we
            // Mojang niet op de hoofdthread om een UUID te vragen.
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
            if (target == null) {
                reply.accept(":x: **" + md(targetName) + "** is nog nooit op de server geweest.");
                return;
            }
            String name = target.getName() != null ? target.getName() : targetName;
            if (target.isBanned()) {
                reply.accept(":warning: **" + md(name) + "** is al verbannen.");
                return;
            }

            target.ban(reason, (Date) null, "Discord: " + by);
            Player online = target.getPlayer();
            if (online != null) {
                online.kick(legacy(banMessage.replace("{reason}", reason)));
            }
            notifyIngameStaff("[Discord] " + by + " verbande " + name + ": " + reason, null);

            reply.accept(":white_check_mark: **" + md(name) + "** is verbannen.");
            logDiscordAction(":hammer: Verbannen via Discord", by, name, "Reden", reason);
        });
    }

    private void handleUnban(String targetName, String by, Consumer<String> reply) {
        onMainThread(() -> {
            OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
            if (target == null || !target.isBanned()) {
                reply.accept(":x: **" + md(targetName) + "** is niet verbannen.");
                return;
            }
            String name = target.getName() != null ? target.getName() : targetName;

            Bukkit.getBanList(BanListType.PROFILE).pardon(target.getPlayerProfile());
            notifyIngameStaff("[Discord] " + by + " hief de ban van " + name + " op.", null);

            reply.accept(":white_check_mark: Ban van **" + md(name) + "** opgeheven.");
            logDiscordAction(":unlock: Ban opgeheven via Discord", by, name, null, null);
        });
    }
}
