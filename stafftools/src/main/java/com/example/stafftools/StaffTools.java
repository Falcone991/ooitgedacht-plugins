package com.example.stafftools;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;

public class StaffTools extends JavaPlugin implements CommandExecutor, Listener {

    private static final String STAFF_TEAM_NAME = "a_stafftools_beheer";
    private static final String PLAYERS_TEAM_NAME = "b_stafftools_spelers";

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
        setupTeams();

        getCommand("report").setExecutor(this);
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

        boolean isStaff = player.hasPermission("stafftools.staff");
        Team targetTeam = isStaff ? staffTeam : playersTeam;
        Team currentTeam = board.getEntryTeam(player.getName());

        if (currentTeam == targetTeam) return;

        if (currentTeam != null) {
            currentTeam.removeEntry(player.getName());
        }
        targetTeam.addEntry(player.getName());
    }

    // ---------- Chat-tag ----------

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!player.hasPermission("stafftools.staff")) return;

        Component prefix = LegacyComponentSerializer.legacyAmpersand().deserialize(chatPrefix);
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
