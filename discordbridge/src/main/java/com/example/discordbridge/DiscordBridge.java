package com.example.discordbridge;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.ban.BanListType;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.RemoteConsoleCommandSender;
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
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server-kant van de OoitGedacht Discord-bot.
 *
 * De bot zelf draait 24/7 als los programma (eigen AMP-instance), zodat hij
 * ook online blijft als AMP de Minecraft-server laat slapen. Deze plugin praat
 * daarom niet zelf met Discord, maar:
 *  - zet logs (joins, chat, reports, ...) klaar in een wachtrij;
 *  - beantwoordt verzoeken van de bot via RCON: "/discordbridge rpc <base64-JSON>".
 *    De bot haalt zo elke paar seconden de wachtrij op ("poll"), vraagt de
 *    status op, en laat kick/ban/msg hier uitvoeren.
 *
 * RCON-commando's draaien op de hoofdthread, dus in rpc() mag alle Bukkit-API.
 */
public class DiscordBridge extends JavaPlugin implements Listener {

    // Kleur van de rand per soort log.
    private static final int COLOR_START = 0x57F287;
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

    // Max aantal logs dat wacht als de bot even niet ophaalt; daarna vallen de oudste weg.
    private static final int MAX_QUEUE = 500;
    // Per "poll" maximaal zoveel logs/tekens teruggeven, zodat RCON-antwoorden klein blijven.
    private static final int POLL_MAX_EVENTS = 10;
    private static final int POLL_MAX_CHARS = 3000;

    private final Gson gson = new Gson();
    private final ConcurrentLinkedQueue<JsonObject> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();

    private MoveWarningWatcher moveWatcher;

    // Instellingen (uit config.yml)
    private boolean logServerStart;
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

    private String msgPrefix;
    private String kickMessage;
    private String banMessage;
    private String defaultReason;

    private String discordInviteLink;
    private String discordCommandMessage;
    private String discordCommandHover;

    // Anti-spam: wanneer iets voor het laatst gelogd is (sleutel -> tijdstip in ms).
    // Concurrent, want de console-meldingen komen van allerlei threads binnen.
    private final Map<String, Long> lastMoveWarning = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCreativeItem = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Nieuwe instellingen uit een update aanvullen in een bestaande config.yml,
        // zonder iets te overschrijven wat je zelf al hebt ingevuld.
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        getCommand("discord").setExecutor(this);
        getCommand("discordbridge").setExecutor(this);

        if (logMoveWarnings) {
            try {
                moveWatcher = new MoveWarningWatcher(this::onMoveWarning, logMovedWrongly);
                moveWatcher.install();
            } catch (Throwable t) {
                getLogger().warning("Kon 'moved too quickly'-meldingen niet meelezen: " + t);
                moveWatcher = null;
            }
        }

        if (logServerStart) {
            enqueue(event("log", COLOR_START, ":green_circle: Server gestart", "De server is online en klaar om te spelen."));
        }

        getLogger().info("DiscordBridge geladen. De Discord-bot haalt logs op via RCON.");
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
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        logServerStart = c.getBoolean("logs.server-start", true);
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

        msgPrefix = c.getString("messages.msg-prefix", "&9[Discord] &b{discord}&7: &r");
        kickMessage = c.getString("messages.kick-message", "&cJe bent gekickt door beheer.\n&7Reden: &f{reason}");
        banMessage = c.getString("messages.ban-message", "&cJe bent verbannen van de server.\n&7Reden: &f{reason}");
        defaultReason = c.getString("messages.default-reason", "Geen reden opgegeven");

        discordInviteLink = c.getString("discord-command.invite-link", "").trim();
        discordCommandMessage = c.getString("discord-command.message", "&9&lDiscord: &b&n{link}");
        discordCommandHover = c.getString("discord-command.hover", "&7Klik om onze Discord te openen");
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("discord")) {
            return handleDiscordCommand(sender);
        }
        if (command.getName().equalsIgnoreCase("discordbridge")) {
            return handleRpcCommand(sender, args);
        }
        return false;
    }

    private boolean handleDiscordCommand(CommandSender sender) {
        if (discordInviteLink.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Er is nog geen Discord-link ingesteld.");
            return true;
        }
        sender.sendMessage(legacy(discordCommandMessage.replace("{link}", discordInviteLink))
                .clickEvent(ClickEvent.openUrl(discordInviteLink))
                .hoverEvent(HoverEvent.showText(legacy(discordCommandHover))));
        return true;
    }

    private boolean handleRpcCommand(CommandSender sender, String[] args) {
        // Alleen de bot (via RCON) en de console; spelers nooit, ook geen OP's.
        if (!(sender instanceof RemoteConsoleCommandSender) && !(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage(ChatColor.RED + "Dit commando is alleen voor de Discord-bot.");
            return true;
        }
        if (args.length != 2 || !args[0].equalsIgnoreCase("rpc")) {
            sender.sendMessage("Gebruik: /discordbridge rpc <base64-JSON> (intern voor de Discord-bot)");
            return true;
        }

        JsonObject response;
        try {
            String json = new String(Base64.getDecoder().decode(args[1]), StandardCharsets.UTF_8);
            response = rpc(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            response = new JsonObject();
            response.addProperty("ok", false);
            response.addProperty("text", ":x: Fout op de server: " + e);
        }
        sender.sendMessage(gson.toJson(response));
        return true;
    }

    // ---------- RPC voor de bot ----------

    private JsonObject rpc(JsonObject req) {
        String op = str(req, "op");
        switch (op) {
            case "poll": return rpcPoll();
            case "status": return rpcStatus();
            case "deaths": return rpcDeaths(str(req, "player"), req.has("limit") ? req.get("limit").getAsInt() : 10);
            case "msg": return rpcMsg(str(req, "by"), str(req, "player"), str(req, "message"));
            case "kick": return rpcKick(str(req, "by"), str(req, "player"), reasonOrDefault(str(req, "reason")));
            case "ban": return rpcBan(str(req, "by"), str(req, "player"), reasonOrDefault(str(req, "reason")));
            case "unban": return rpcUnban(str(req, "by"), str(req, "player"));
            default:
                return result(false, ":x: Onbekend verzoek '" + op + "'. Zijn de bot en de plugin allebei bijgewerkt?");
        }
    }

    private static String str(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private String reasonOrDefault(String reason) {
        return reason == null || reason.isBlank() ? defaultReason : reason;
    }

    private static JsonObject result(boolean ok, String text) {
        JsonObject r = new JsonObject();
        r.addProperty("ok", ok);
        r.addProperty("text", text);
        return r;
    }

    private JsonArray onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        JsonArray arr = new JsonArray();
        names.forEach(arr::add);
        return arr;
    }

    /** Geeft de volgende logs uit de wachtrij, plus de spelerslijst (voor status en autocomplete). */
    private JsonObject rpcPoll() {
        JsonArray events = new JsonArray();
        int chars = 0;
        while (events.size() < POLL_MAX_EVENTS) {
            JsonObject next = queue.peek();
            if (next == null) break;
            int length = next.toString().length();
            if (!events.isEmpty() && chars + length > POLL_MAX_CHARS) break;
            queue.poll();
            queueSize.decrementAndGet();
            events.add(next);
            chars += length;
        }

        JsonObject r = new JsonObject();
        r.add("events", events);
        r.addProperty("more", !queue.isEmpty());
        r.add("players", onlinePlayerNames());
        r.addProperty("max", Bukkit.getMaxPlayers());
        return r;
    }

    private JsonObject rpcStatus() {
        JsonObject r = new JsonObject();
        r.add("players", onlinePlayerNames());
        r.addProperty("max", Bukkit.getMaxPlayers());
        r.addProperty("tps", Math.min(20.0, Bukkit.getTPS()[0]));
        r.addProperty("started", ManagementFactory.getRuntimeMXBean().getStartTime());
        JsonObject timeline = timelineInfo();
        if (timeline != null) r.add("timeline", timeline);
        return r;
    }

    /**
     * Leest de status rechtstreeks uit de SurvivalTimeline-plugin (config +
     * data.yml), zodat die plugin zelf niet aangepast hoeft te worden.
     */
    private JsonObject timelineInfo() {
        Plugin timeline = Bukkit.getPluginManager().getPlugin("SurvivalTimeline");
        if (timeline == null) return null;

        File dataFile = new File(timeline.getDataFolder(), "data.yml");
        if (!dataFile.exists()) return null;
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        if (!data.contains("start-time")) return null;
        FileConfiguration cfg = timeline.getConfig();

        JsonObject t = new JsonObject();
        t.addProperty("start", data.getLong("start-time"));
        t.addProperty("hardcore", data.getBoolean("hardcore-activated", false));
        t.addProperty("nether", data.getBoolean("nether-unlocked", false));
        t.addProperty("end", data.getBoolean("end-unlocked", false));
        t.addProperty("weeksBeforeHardcore", cfg.getInt("weeks-before-hardcore", 3));
        t.addProperty("netherOpenWeek", cfg.getInt("nether-open-week", 2));
        t.addProperty("endOpenWeek", cfg.getInt("end-open-week", 3));

        String worldName = cfg.getString("main-world", "");
        World world = worldName == null || worldName.isEmpty()
                ? (Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().get(0))
                : Bukkit.getWorld(worldName);
        if (world != null) t.addProperty("border", Math.round(world.getWorldBorder().getSize()));
        return t;
    }

    private JsonObject rpcDeaths(String only, int limit) {
        JsonObject r = new JsonObject();
        if (!only.isEmpty()) {
            OfflinePlayer player = Bukkit.getOfflinePlayerIfCached(only);
            if (player == null) {
                r.addProperty("error", "**" + md(only) + "** is nog nooit op de server geweest.");
                return r;
            }
            r.addProperty("player", player.getName() != null ? player.getName() : only);
            r.addProperty("deaths", player.getStatistic(Statistic.DEATHS));
            return r;
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

        JsonArray top = new JsonArray();
        for (int i = 0; i < Math.min(Math.max(1, limit), list.size()); i++) {
            JsonObject e = new JsonObject();
            e.addProperty("name", list.get(i).getKey());
            e.addProperty("deaths", list.get(i).getValue());
            top.add(e);
        }
        r.add("top", top);
        return r;
    }

    private void notifyIngameStaff(String text, Player except) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(except)) continue;
            if (online.hasPermission("discordbridge.seemsg")) {
                online.sendMessage(ChatColor.GRAY + text);
            }
        }
    }

    private void logDiscordAction(String title, String by, String target, String detailName, String detail) {
        if (!logDiscordActions) return;
        JsonObject e = playerEvent(target, COLOR_DISCORD_ACTION, title, null);
        field(e, "Door (Discord)", md(by), true);
        field(e, "Speler", md(target), true);
        if (detailName != null) field(e, detailName, md(detail), false);
        enqueue(e);
    }

    private JsonObject rpcMsg(String by, String targetName, String message) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return result(false, ":x: **" + md(targetName) + "** is niet online.");

        target.sendMessage(legacy(msgPrefix.replace("{discord}", by)).append(Component.text(message)));
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        notifyIngameStaff("[Discord] " + by + " -> " + target.getName() + ": " + message, target);

        logDiscordAction(":speech_left: Bericht via Discord", by, target.getName(), "Bericht", message);
        return result(true, ":white_check_mark: Bericht verstuurd naar **" + md(target.getName()) + "**.");
    }

    private JsonObject rpcKick(String by, String targetName, String reason) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) return result(false, ":x: **" + md(targetName) + "** is niet online.");

        target.kick(legacy(kickMessage.replace("{reason}", reason)));
        notifyIngameStaff("[Discord] " + by + " kickte " + target.getName() + ": " + reason, null);

        logDiscordAction(":boot: Gekickt via Discord", by, target.getName(), "Reden", reason);
        return result(true, ":white_check_mark: **" + md(target.getName()) + "** is gekickt.");
    }

    private JsonObject rpcBan(String by, String targetName, String reason) {
        // Alleen spelers die ooit op de server zijn geweest; zo hoeven we
        // Mojang niet op de hoofdthread om een UUID te vragen.
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null) return result(false, ":x: **" + md(targetName) + "** is nog nooit op de server geweest.");

        String name = target.getName() != null ? target.getName() : targetName;
        if (target.isBanned()) return result(false, ":warning: **" + md(name) + "** is al verbannen.");

        target.ban(reason, (Date) null, "Discord: " + by);
        Player online = target.getPlayer();
        if (online != null) online.kick(legacy(banMessage.replace("{reason}", reason)));
        notifyIngameStaff("[Discord] " + by + " verbande " + name + ": " + reason, null);

        logDiscordAction(":hammer: Verbannen via Discord", by, name, "Reden", reason);
        return result(true, ":white_check_mark: **" + md(name) + "** is verbannen.");
    }

    private JsonObject rpcUnban(String by, String targetName) {
        OfflinePlayer target = Bukkit.getOfflinePlayerIfCached(targetName);
        if (target == null || !target.isBanned()) return result(false, ":x: **" + md(targetName) + "** is niet verbannen.");

        String name = target.getName() != null ? target.getName() : targetName;
        Bukkit.getBanList(BanListType.PROFILE).pardon(target.getPlayerProfile());
        notifyIngameStaff("[Discord] " + by + " hief de ban van " + name + " op.", null);

        logDiscordAction(":unlock: Ban opgeheven via Discord", by, name, null, null);
        return result(true, ":white_check_mark: Ban van **" + md(name) + "** opgeheven.");
    }

    // ---------- Wachtrij met logs ----------

    /** Basis-log. channel = "log" of "chat"; title/description mogen null zijn. */
    private static JsonObject event(String channel, int color, String title, String description) {
        JsonObject e = new JsonObject();
        e.addProperty("channel", channel);
        e.addProperty("color", color);
        e.addProperty("time", System.currentTimeMillis());
        if (title != null) e.addProperty("title", cut(title, 256));
        if (description != null) e.addProperty("description", cut(description, 4000));
        return e;
    }

    /** Zelfde, met de speler bovenaan (de bot zet er het Minecraft-hoofd bij). */
    private static JsonObject playerEvent(String player, int color, String title, String description) {
        JsonObject e = event("log", color, title, description);
        e.addProperty("author", player);
        return e;
    }

    private static void field(JsonObject e, String name, String value, boolean inline) {
        JsonArray fields = e.has("fields") ? e.getAsJsonArray("fields") : new JsonArray();
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value == null || value.isEmpty() ? "-" : cut(value, 1024));
        f.addProperty("inline", inline);
        fields.add(f);
        e.add("fields", fields);
    }

    /** Markeert een log als "belangrijk": de bot kan dan een rol pingen (zie ping-on in de bot-config). */
    private static JsonObject alert(JsonObject e, String type, String subject) {
        e.addProperty("alert", type);
        e.addProperty("subject", subject);
        return e;
    }

    private void enqueue(JsonObject e) {
        queue.add(e);
        if (queueSize.incrementAndGet() > MAX_QUEUE && queue.poll() != null) {
            queueSize.decrementAndGet();
        }
    }

    // ---------- Hulpjes ----------

    /** Discord-markdown onschadelijk maken (bv. _ in spelersnamen). */
    static String md(String text) {
        return text == null ? "" : text.replaceAll("([\\\\*_`~|>])", "\\\\$1");
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 3) + "...";
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

    private static String code(String text) {
        return "`" + text.replace("`", "'") + "`";
    }

    /** true als 'key' het afgelopen 'seconds' al gelogd is; zo niet, wordt het nu als gelogd gemarkeerd. */
    private static boolean recentlyLogged(Map<String, Long> map, String key, int seconds) {
        long now = System.currentTimeMillis();
        Long last = map.get(key);
        if (last != null && now - last < seconds * 1000L) return true;
        map.put(key, now);
        return false;
    }

    /** "minecraft:give" -> "give". */
    private static String stripNamespace(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon >= 0 ? lower.substring(colon + 1) : lower;
    }

    // ---------- Events ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!logJoin) return;
        String name = event.getPlayer().getName();
        enqueue(playerEvent(name, COLOR_JOIN, ":inbox_tray: Ingelogd", "**" + md(name) + "** is de server binnengekomen."));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (!logLeave) return;
        String name = event.getPlayer().getName();
        enqueue(playerEvent(name, COLOR_LEAVE, ":outbox_tray: Uitgelogd", "**" + md(name) + "** heeft de server verlaten."));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!logDeath) return;
        Component deathMessage = event.deathMessage();
        String name = event.getEntity().getName();
        String text = deathMessage != null ? plain(deathMessage) : name + " is doodgegaan";
        enqueue(playerEvent(name, COLOR_DEATH, ":skull: Dood", md(text)));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!logChat) return;
        String message = plain(event.message());
        if (message.isBlank()) return;
        JsonObject e = playerEvent(event.getPlayer().getName(), COLOR_CHAT, null, md(message));
        e.addProperty("channel", "chat");
        enqueue(e);
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

        String name = event.getPlayer().getName();
        JsonObject e = playerEvent(name, COLOR_GAMEMODE, ":video_game: Gamemode gewijzigd",
                prettyName(event.getPlayer().getGameMode().name()) + " → **"
                        + prettyName(event.getNewGameMode().name()) + "**");
        field(e, "Oorzaak", cause, true);
        enqueue(alert(e, "gamemode-changes", name));
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

        enqueue(alert(playerEvent(player.getName(), COLOR_CREATIVE, ":package: Item uit creative",
                "**" + item.getAmount() + "x " + prettyName(item.getType().name()) + "**"),
                "creative-items", player.getName()));
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

        JsonObject e = playerEvent(who, COLOR_MOVE, title, explanation);
        field(e, "Console", code(cut(consoleLine, 1000)), false);
        enqueue(alert(e, wrongly ? "moved-wrongly" : "moved-too-quickly", who));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().substring(1);
        String[] parts = command.split(" ", 3);
        String label = stripNamespace(parts[0]);
        String name = event.getPlayer().getName();

        // "/report <speler> <reden>" van de StaffTools-plugin meelezen.
        if (logReports && label.equals("report") && parts.length == 3) {
            JsonObject e = playerEvent(name, COLOR_REPORT, ":triangular_flag_on_post: Nieuwe report", null);
            field(e, "Gemeld door", md(name), true);
            field(e, "Speler", md(parts[1]), true);
            field(e, "Reden", md(parts[2]), false);
            enqueue(alert(e, "reports", name));
            return;
        }

        if (logCommands && watchedCommands.contains(label)) {
            enqueue(alert(playerEvent(name, COLOR_COMMAND, ":keyboard: Commando gebruikt", code("/" + cut(command, 1500))),
                    "commands", name));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        if (!logCommands) return;
        // Command blocks niet loggen (die kunnen elke tick afgaan), en de bot zelf ook niet.
        if (event.getSender() instanceof BlockCommandSender) return;

        String command = event.getCommand().startsWith("/") ? event.getCommand().substring(1) : event.getCommand();
        String label = stripNamespace(command.split(" ", 2)[0]);
        if (watchedCommands.contains(label)) {
            String who = event.getSender().getName();
            enqueue(alert(event("log", COLOR_COMMAND, ":keyboard: Commando gebruikt (" + who + ")",
                    code("/" + cut(command, 1500))), "commands", who));
        }
    }
}
