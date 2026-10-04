package com.example.stafftools;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StaffTools extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final String STAFF_TEAM_NAME = "a_stafftools_beheer";
    private static final String PLAYERS_TEAM_NAME = "b_stafftools_spelers";
    // Eigen prefix = eigen team per speler: "b_p_" + begin van de UUID. Sorteert in
    // de tab-lijst tussen beheer en gewone spelers in.
    private static final String PREFIX_TEAM_START = "b_p_";
    private static final int MAX_PREFIX_LENGTH = 64;

    // Eigen prefixes (UUID -> tekst met &-codes). Concurrent: de chat draait op een andere thread.
    private final Map<UUID, String> prefixes = new ConcurrentHashMap<>();
    private File prefixFile;
    private YamlConfiguration prefixData;

    private String tabPrefix;
    private String chatPrefix;
    private long refreshIntervalTicks;
    private String sgmPrefix;
    private boolean sgmSoundEnabled;

    private String discordWebhookUrl;
    private String discordUsername;
    private boolean discordLogJoin;
    private boolean discordLogLeave;
    private boolean discordLogReports;
    private boolean discordLogSgm;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadPrefixes();
        setupTeams();

        getCommand("report").setExecutor(this);
        getCommand("prefix").setExecutor(this);
        getCommand("prefix").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::refreshAllStaffTeams, refreshIntervalTicks, refreshIntervalTicks);

        getLogger().info("StaffTools geladen. Discord-webhook: " + (discordWebhookUrl.isEmpty() ? "niet ingesteld" : "ingesteld"));
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        tabPrefix = c.getString("staff.tab-prefix", "&c[Beheer] &r");
        chatPrefix = c.getString("staff.chat-prefix", "&c[Beheer] &r");
        refreshIntervalTicks = c.getLong("staff.refresh-interval-ticks", 100L);
        sgmPrefix = c.getString("staff.sgm-prefix", "&d&l[Beheer] &r");
        sgmSoundEnabled = c.getBoolean("staff.sgm-sound-enabled", true);

        discordWebhookUrl = c.getString("discord.webhook-url", "");
        discordUsername = c.getString("discord.username", "OoitGedacht SMP");
        discordLogJoin = c.getBoolean("discord.log-join", true);
        discordLogLeave = c.getBoolean("discord.log-leave", true);
        discordLogReports = c.getBoolean("discord.log-reports", true);
        discordLogSgm = c.getBoolean("discord.log-sgm", false);
    }

    // ---------- Tab-lijst (teams) ----------

    private void setupTeams() {
        Scoreboard board = getMainScoreboard();
        if (board == null) return;

        Team staffTeam = board.getTeam(STAFF_TEAM_NAME);
        if (staffTeam == null) {
            staffTeam = board.registerNewTeam(STAFF_TEAM_NAME);
        }
        staffTeam.setPrefix(ChatColor.translateAlternateColorCodes('&', tabPrefix));

        Team playersTeam = board.getTeam(PLAYERS_TEAM_NAME);
        if (playersTeam == null) {
            board.registerNewTeam(PLAYERS_TEAM_NAME);
        }

        // Prefix-teams opruimen van spelers die geen eigen prefix meer hebben.
        Set<String> wanted = new HashSet<>();
        for (UUID uuid : prefixes.keySet()) wanted.add(prefixTeamName(uuid));
        for (Team team : new ArrayList<>(board.getTeams())) {
            if (team.getName().startsWith(PREFIX_TEAM_START) && !wanted.contains(team.getName())) {
                team.unregister();
            }
        }

        refreshAllStaffTeams();
    }

    private Scoreboard getMainScoreboard() {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        return manager == null ? null : manager.getMainScoreboard();
    }

    private void refreshAllStaffTeams() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshStaffTeam(player);
        }
    }

    private void refreshStaffTeam(Player player) {
        Scoreboard board = getMainScoreboard();
        if (board == null) return;

        Team staffTeam = board.getTeam(STAFF_TEAM_NAME);
        Team playersTeam = board.getTeam(PLAYERS_TEAM_NAME);
        if (staffTeam == null || playersTeam == null) return;

        // Een eigen prefix gaat vóór de [Beheer]-tag.
        Team targetTeam = prefixTeam(board, player.getUniqueId());
        if (targetTeam == null) {
            targetTeam = player.hasPermission("stafftools.staff") ? staffTeam : playersTeam;
        }
        Team currentTeam = board.getEntryTeam(player.getName());

        if (currentTeam == targetTeam) return;

        if (currentTeam != null) {
            currentTeam.removeEntry(player.getName());
        }
        targetTeam.addEntry(player.getName());
    }

    // ---------- Eigen prefixes ----------

    private void loadPrefixes() {
        prefixFile = new File(getDataFolder(), "prefixes.yml");
        prefixData = YamlConfiguration.loadConfiguration(prefixFile);
        ConfigurationSection section = prefixData.getConfigurationSection("players");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String prefix = section.getString(key + ".prefix");
            if (prefix == null || prefix.isEmpty()) continue;
            try {
                prefixes.put(UUID.fromString(key), prefix);
            } catch (IllegalArgumentException ignored) {
                // ongeldige regel overslaan
            }
        }
    }

    private void savePrefixes() {
        try {
            prefixData.save(prefixFile);
        } catch (IOException e) {
            getLogger().severe("Kon prefixes.yml niet opslaan: " + e.getMessage());
        }
    }

    private static String prefixTeamName(UUID uuid) {
        return PREFIX_TEAM_START + uuid.toString().replace("-", "").substring(0, 12);
    }

    /** Wat er vóór de naam komt: de prefix zelf, dan reset en een spatie. */
    private static Component prefixComponent(String prefix) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(prefix.trim() + "&r ");
    }

    /** Team met de eigen prefix van deze speler (aangemaakt/bijgewerkt), of null als die er geen heeft. */
    private Team prefixTeam(Scoreboard board, UUID uuid) {
        String prefix = prefixes.get(uuid);
        if (prefix == null) return null;
        String name = prefixTeamName(uuid);
        Team team = board.getTeam(name);
        if (team == null) team = board.registerNewTeam(name);
        Component wanted = prefixComponent(prefix);
        if (!wanted.equals(team.prefix())) team.prefix(wanted);
        return team;
    }

    private boolean handlePrefix(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafftools.prefix")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";

        if (sub.equals("list")) {
            if (prefixes.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "Er zijn nog geen prefixes ingesteld.");
                return true;
            }
            sender.sendMessage(ChatColor.YELLOW + "=== Prefixes ===");
            for (Map.Entry<UUID, String> entry : prefixes.entrySet()) {
                String name = prefixData.getString("players." + entry.getKey() + ".name", entry.getKey().toString());
                sender.sendMessage(Component.text()
                        .append(prefixComponent(entry.getValue()))
                        .append(Component.text(name))
                        .append(Component.text("  (" + entry.getValue() + ")", net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY))
                        .build());
            }
            return true;
        }

        if ((!sub.equals("set") || args.length < 3) && (!sub.equals("remove") || args.length != 2)) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik:");
            sender.sendMessage(ChatColor.YELLOW + "  /prefix set <speler> <prefix>" + ChatColor.GRAY + "  bv. /prefix set Steve &6[VIP]");
            sender.sendMessage(ChatColor.YELLOW + "  /prefix remove <speler>");
            sender.sendMessage(ChatColor.YELLOW + "  /prefix list");
            sender.sendMessage(ChatColor.GRAY + "Kleuren met &: &c rood, &6 goud, &e geel, &a groen, &b aqua, &9 blauw, &d roze, &l vet.");
            return true;
        }

        OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[1]);
        if (target == null || target.getName() == null) {
            sender.sendMessage(ChatColor.RED + "Speler '" + args[1] + "' is nog nooit op de server geweest.");
            return true;
        }
        UUID uuid = target.getUniqueId();
        String path = "players." + uuid;

        if (sub.equals("set")) {
            String prefix = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            if (prefix.length() > MAX_PREFIX_LENGTH) {
                sender.sendMessage(ChatColor.RED + "Die prefix is te lang (max. " + MAX_PREFIX_LENGTH + " tekens, kleurcodes meegeteld).");
                return true;
            }
            prefixes.put(uuid, prefix);
            prefixData.set(path + ".name", target.getName());
            prefixData.set(path + ".prefix", prefix);
            savePrefixes();
            sender.sendMessage(Component.text()
                    .append(Component.text("Prefix ingesteld: ", net.kyori.adventure.text.format.NamedTextColor.GREEN))
                    .append(prefixComponent(prefix))
                    .append(Component.text(target.getName()))
                    .build());
        } else {
            if (prefixes.remove(uuid) == null) {
                sender.sendMessage(ChatColor.RED + target.getName() + " heeft geen eigen prefix.");
                return true;
            }
            prefixData.set(path, null);
            savePrefixes();
            Scoreboard board = getMainScoreboard();
            Team team = board == null ? null : board.getTeam(prefixTeamName(uuid));
            if (team != null) team.unregister();
            sender.sendMessage(ChatColor.GREEN + "Prefix van " + target.getName() + " verwijderd.");
        }

        Player online = target.getPlayer();
        if (online != null) refreshStaffTeam(online);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("prefix") || !sender.hasPermission("stafftools.prefix")) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String option : new String[]{"set", "remove", "list"}) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(option);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("set") || args[0].equalsIgnoreCase("remove"))) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            if (args[0].equalsIgnoreCase("remove")) {
                for (UUID uuid : prefixes.keySet()) {
                    String name = prefixData.getString("players." + uuid + ".name", "");
                    if (name.toLowerCase(Locale.ROOT).startsWith(typed)) result.add(name);
                }
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) result.add(p.getName());
                }
            }
        }
        return result;
    }

    // ---------- Chat-tag ----------

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String custom = prefixes.get(player.getUniqueId());
        Component prefix;
        if (custom != null) {
            prefix = prefixComponent(custom);
        } else if (player.hasPermission("stafftools.staff")) {
            prefix = LegacyComponentSerializer.legacyAmpersand().deserialize(chatPrefix);
        } else {
            return;
        }
        event.renderer((source, sourceDisplayName, message, viewer) ->
                Component.text().append(prefix).append(sourceDisplayName).append(Component.text(": ")).append(message).build());
    }

    // ---------- Join/quit ----------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> refreshStaffTeam(player));

        if (discordLogJoin) {
            sendDiscordMessage("**" + escapeMarkdown(player.getName()) + "** is ingelogd.");
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (discordLogLeave) {
            sendDiscordMessage("**" + escapeMarkdown(event.getPlayer().getName()) + "** is uitgelogd.");
        }
    }

    // ---------- /report ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("sgm")) {
            return handleSgm(sender, args);
        }
        if (command.getName().equalsIgnoreCase("prefix")) {
            return handlePrefix(sender, args);
        }
        if (!command.getName().equalsIgnoreCase("report")) return false;

        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /report <speler> <reden>");
            return true;
        }

        Player reporter = (Player) sender;
        String target = args[0];
        String reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        String staffMessage = ChatColor.translateAlternateColorCodes('&', "&c&l[Report] &f"
                + reporter.getName() + " &7meldde &f" + target + "&7: &f" + reason);

        boolean anyStaffOnline = false;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("stafftools.staff")) {
                online.sendMessage(staffMessage);
                anyStaffOnline = true;
            }
        }

        getLogger().info("[Report] " + reporter.getName() + " meldde " + target + ": " + reason);

        reporter.sendMessage(ChatColor.GREEN + "Je melding is verstuurd naar de beheerders.");
        if (!anyStaffOnline) {
            reporter.sendMessage(ChatColor.GRAY + "(Er is nu geen beheer online, maar je melding staat wel in de server-log"
                    + (discordWebhookUrl.isEmpty() ? "." : " en op Discord."));
        }

        if (discordLogReports) {
            sendDiscordMessage(":triangular_flag_on_post: **Report** \u2014 "
                    + escapeMarkdown(reporter.getName()) + " meldde **" + escapeMarkdown(target) + "**: "
                    + escapeMarkdown(reason));
        }

        return true;
    }

    // ---------- /sgm (staff-naar-speler priv\u00e9bericht) ----------

    private boolean handleSgm(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stafftools.staff")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /sgm <speler> <bericht>");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Speler '" + args[0] + "' is niet online.");
            return true;
        }

        String message = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        String senderName = sender instanceof Player ? ((Player) sender).getName() : "Console";
        String coloredPrefix = ChatColor.translateAlternateColorCodes('&', sgmPrefix);

        // Naar de ontvanger
        target.sendMessage(coloredPrefix + ChatColor.WHITE + message);
        if (sgmSoundEnabled) {
            target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.2f);
        }

        // Bevestiging voor de afzender
        sender.sendMessage(ChatColor.GRAY + "[Naar " + target.getName() + "] " + ChatColor.WHITE + message);

        // Andere beheer ziet mee, voor overzicht/transparantie
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(target) || online.getName().equals(senderName)) continue;
            if (online.hasPermission("stafftools.staff")) {
                online.sendMessage(ChatColor.GRAY + "[SGM] " + ChatColor.RED + senderName
                        + ChatColor.GRAY + " -> " + ChatColor.WHITE + target.getName()
                        + ChatColor.GRAY + ": " + message);
            }
        }

        getLogger().info("[SGM] " + senderName + " -> " + target.getName() + ": " + message);

        if (discordLogSgm) {
            sendDiscordMessage("\ud83d\udcac **SGM** \u2014 " + escapeMarkdown(senderName) + " -> **"
                    + escapeMarkdown(target.getName()) + "**: " + escapeMarkdown(message));
        }

        return true;
    }

    // ---------- Discord webhook ----------

    private void sendDiscordMessage(String content) {
        if (discordWebhookUrl == null || discordWebhookUrl.isEmpty()) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                String json = "{\"username\":\"" + escapeJson(discordUsername) + "\",\"content\":\"" + escapeJson(content) + "\"}";

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(discordWebhookUrl))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json))
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() >= 300) {
                    getLogger().warning("Discord-webhook gaf status " + response.statusCode() + " terug.");
                }
            } catch (Exception e) {
                getLogger().warning("Kon bericht niet naar Discord sturen: " + e.getMessage());
            }
        });
    }

    private String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String escapeMarkdown(String text) {
        return text.replace("_", "\\_").replace("*", "\\*").replace("`", "\\`");
    }
}
