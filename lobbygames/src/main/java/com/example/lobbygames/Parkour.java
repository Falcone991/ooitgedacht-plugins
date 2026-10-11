package com.example.lobbygames;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Parkour: beheer wijst start, checkpoints (in volgorde) en finish aan op de plek
 * waar ze staan. Spelers starten door op de start te stappen; de tijd loopt onderin
 * beeld. Val je onder het laagste punt, dan sta je weer op je laatste checkpoint.
 * Vliegen, creative of een teleport stopt je poging.
 */
final class Parkour implements TabExecutor, Listener {

    private static final class Run {
        long startedAt;
        int checkpoint = -1; // -1 = nog bij de start
    }

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    private Location start;
    private Location finish;
    private final List<Location> checkpoints = new ArrayList<>();
    private Double fallY; // null = automatisch (laagste punt - 4)

    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Long> best = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> ownTeleport = new HashSet<>();

    Parkour(JavaPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "parkour.yml");
        data = YamlConfiguration.loadConfiguration(file);
        load();
    }

    String describe() {
        if (start == null || finish == null) return "nog niet ingesteld";
        return checkpoints.size() + " checkpoint(s)";
    }

    // ---------- Opslag ----------

    private void load() {
        start = LobbyGames.loadLocation(data.getConfigurationSection("start"));
        finish = LobbyGames.loadLocation(data.getConfigurationSection("finish"));
        checkpoints.clear();
        ConfigurationSection cps = data.getConfigurationSection("checkpoints");
        if (cps != null) {
            List<String> keys = new ArrayList<>(cps.getKeys(false));
            keys.sort((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
            for (String key : keys) {
                Location loc = LobbyGames.loadLocation(cps.getConfigurationSection(key));
                if (loc != null) checkpoints.add(loc);
            }
        }
        fallY = data.isDouble("fall-y") || data.isInt("fall-y") ? data.getDouble("fall-y") : null;
        ConfigurationSection times = data.getConfigurationSection("times");
        if (times != null) {
            for (String key : times.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    best.put(uuid, times.getLong(key + ".ms"));
                    names.put(uuid, times.getString(key + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                    // overslaan
                }
            }
        }
    }

    private void save() {
        LobbyGames.saveLocation(data, "start", start);
        LobbyGames.saveLocation(data, "finish", finish);
        data.set("checkpoints", null);
        for (int i = 0; i < checkpoints.size(); i++) {
            ConfigurationSection cps = data.getConfigurationSection("checkpoints");
            if (cps == null) cps = data.createSection("checkpoints");
            LobbyGames.saveLocation(cps, String.valueOf(i), checkpoints.get(i));
        }
        data.set("fall-y", fallY);
        data.set("times", null);
        for (Map.Entry<UUID, Long> e : best.entrySet()) {
            data.set("times." + e.getKey() + ".name", names.get(e.getKey()));
            data.set("times." + e.getKey() + ".ms", e.getValue());
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Kon parkour.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Spelen ----------

    /** Staat de speler op dit punt (zelfde blok, of een blok erboven/eronder)? */
    private static boolean at(Location player, Location point) {
        return point != null && player.getWorld() == point.getWorld()
                && player.getBlockX() == point.getBlockX() && player.getBlockZ() == point.getBlockZ()
                && Math.abs(player.getBlockY() - point.getBlockY()) <= 1;
    }

    private double fallLimit() {
        if (fallY != null) return fallY;
        double lowest = Math.min(start.getY(), finish.getY());
        for (Location cp : checkpoints) lowest = Math.min(lowest, cp.getY());
        return lowest - 4;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (start == null || finish == null) return;
        Location to = event.getTo();
        Location from = event.getFrom();
        Player p = event.getPlayer();
        Run run = runs.get(p.getUniqueId());
        if (run == null && to.getWorld() != start.getWorld()) return;

        if (run != null && (p.isFlying() || p.isGliding() || p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR)) {
            stop(p, "je vloog of zit in creative/spectator.");
            return;
        }

        // Gevallen? Terug naar je laatste checkpoint.
        if (run != null && to.getWorld() == start.getWorld() && to.getY() < fallLimit()) {
            Location back = run.checkpoint >= 0 ? checkpoints.get(run.checkpoint) : start;
            p.setFallDistance(0);
            ownTeleport.add(p.getUniqueId());
            p.teleport(back);
            ownTeleport.remove(p.getUniqueId());
            p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.6f, 1.4f);
            return;
        }

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;
        if (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR || p.isFlying()) return;

        if (at(to, start)) {
            boolean restart = run != null;
            run = new Run();
            run.startedAt = System.currentTimeMillis();
            runs.put(p.getUniqueId(), run);
            p.sendActionBar(LobbyGames.legacy(restart ? "&eParkour opnieuw gestart!" : "&aParkour gestart! Succes!"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.4f);
            return;
        }
        if (run == null) return;

        for (int i = run.checkpoint + 1; i < checkpoints.size(); i++) {
            if (!at(to, checkpoints.get(i))) continue;
            if (i == run.checkpoint + 1) {
                run.checkpoint = i;
                p.sendMessage(ChatColor.GREEN + "Checkpoint " + (i + 1) + "/" + checkpoints.size() + " "
                        + ChatColor.GRAY + "(" + LobbyGames.formatTime(System.currentTimeMillis() - run.startedAt) + ")");
                p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
            } else {
                p.sendActionBar(LobbyGames.legacy("&cJe hebt checkpoint " + (run.checkpoint + 2) + " overgeslagen!"));
            }
            return;
        }

        if (at(to, finish)) {
            if (run.checkpoint < checkpoints.size() - 1) {
                p.sendActionBar(LobbyGames.legacy("&cJe mist nog checkpoint(s)! Ga terug."));
                return;
            }
            finish(p, run);
        }
    }

    private void finish(Player p, Run run) {
        runs.remove(p.getUniqueId());
        long time = System.currentTimeMillis() - run.startedAt;
        UUID uuid = p.getUniqueId();
        Long previous = best.get(uuid);
        long recordBefore = best.values().stream().mapToLong(Long::longValue).min().orElse(Long.MAX_VALUE);
        boolean personalBest = previous == null || time < previous;
        names.put(uuid, p.getName());
        if (personalBest) {
            best.put(uuid, time);
            save();
        }

        p.showTitle(Title.title(LobbyGames.legacy("&a&lFINISH!"), LobbyGames.legacy("&f" + LobbyGames.formatTime(time)
                        + (personalBest && previous != null ? " &6- persoonlijk record!" : "")),
                Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofSeconds(1))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
        p.sendMessage(ChatColor.GREEN + "Parkour gehaald in " + ChatColor.WHITE + LobbyGames.formatTime(time) + ChatColor.GREEN + "!"
                + (previous != null ? ChatColor.GRAY + " (jouw record: " + LobbyGames.formatTime(Math.min(previous, time)) + ")" : ""));

        if (time < recordBefore) {
            for (Player other : p.getWorld().getPlayers()) {
                other.sendMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Nieuw parkour-record! " + ChatColor.GOLD
                        + p.getName() + " in " + LobbyGames.formatTime(time) + ".");
            }
        }
    }

    private void stop(Player p, String reason) {
        if (runs.remove(p.getUniqueId()) == null) return;
        if (reason != null) p.sendActionBar(LobbyGames.legacy("&cParkour gestopt: " + reason));
    }

    /** Tijd onderin beeld. */
    void showTimers() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Run> e : runs.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            Run run = e.getValue();
            String cp = checkpoints.isEmpty() ? "" : " &8| &7checkpoint " + (run.checkpoint + 1) + "/" + checkpoints.size();
            p.sendActionBar(LobbyGames.legacy("&e⏱ " + LobbyGames.formatTime(now - run.startedAt) + cp));
        }
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (ownTeleport.contains(event.getPlayer().getUniqueId())) return;
        stop(event.getPlayer(), "je bent geteleporteerd.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        runs.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        Player p = sender instanceof Player ? (Player) sender : null;

        switch (sub) {
            case "top":
                top(sender);
                return true;
            case "terug": {
                if (p == null) return true;
                Run run = runs.get(p.getUniqueId());
                if (run == null) {
                    p.sendMessage(ChatColor.RED + "Je doet nu niet mee aan de parkour.");
                    return true;
                }
                ownTeleport.add(p.getUniqueId());
                p.teleport(run.checkpoint >= 0 ? checkpoints.get(run.checkpoint) : start);
                ownTeleport.remove(p.getUniqueId());
                return true;
            }
            case "stop":
                if (p != null) {
                    if (runs.containsKey(p.getUniqueId())) stop(p, "je bent gestopt.");
                    else p.sendMessage(ChatColor.RED + "Je doet nu niet mee aan de parkour.");
                }
                return true;
            default:
                break;
        }

        if (!sender.hasPermission("lobbygames.admin") || sub.equals("help")) {
            help(sender);
            return true;
        }
        switch (sub) {
            case "setstart":
                if (p == null) return true;
                start = LobbyGames.blockCenter(p.getLocation());
                save();
                p.sendMessage(ChatColor.GREEN + "Start gezet. Zet nu de checkpoints (in volgorde) met /parkour addcheckpoint, en de finish met /parkour setfinish.");
                return true;
            case "addcheckpoint":
                if (p == null) return true;
                checkpoints.add(LobbyGames.blockCenter(p.getLocation()));
                save();
                p.sendMessage(ChatColor.GREEN + "Checkpoint " + checkpoints.size() + " gezet.");
                return true;
            case "clearcheckpoints":
                checkpoints.clear();
                save();
                sender.sendMessage(ChatColor.GREEN + "Alle checkpoints weggehaald.");
                return true;
            case "setfinish":
                if (p == null) return true;
                finish = LobbyGames.blockCenter(p.getLocation());
                save();
                p.sendMessage(ChatColor.GREEN + "Finish gezet. De parkour is klaar: " + describe() + ".");
                return true;
            case "setfall":
                if (args.length > 1 && args[1].equalsIgnoreCase("auto")) {
                    fallY = null;
                } else if (p != null) {
                    fallY = p.getLocation().getY();
                } else {
                    return true;
                }
                save();
                sender.sendMessage(ChatColor.GREEN + "Valhoogte: " + (fallY == null ? "automatisch (laagste punt - 4)" : "y " + Math.round(fallY)) + ".");
                return true;
            case "resettimes":
                best.clear();
                save();
                sender.sendMessage(ChatColor.GREEN + "Alle parkour-tijden gewist.");
                return true;
            case "info":
                sender.sendMessage(ChatColor.GOLD + "=== Parkour ===");
                sender.sendMessage(ChatColor.GRAY + "Start: " + ChatColor.WHITE + describeLoc(start));
                for (int i = 0; i < checkpoints.size(); i++) {
                    sender.sendMessage(ChatColor.GRAY + "Checkpoint " + (i + 1) + ": " + ChatColor.WHITE + describeLoc(checkpoints.get(i)));
                }
                sender.sendMessage(ChatColor.GRAY + "Finish: " + ChatColor.WHITE + describeLoc(finish));
                if (start != null && finish != null) {
                    sender.sendMessage(ChatColor.GRAY + "Valhoogte: " + ChatColor.WHITE + "y " + Math.round(fallLimit())
                            + (fallY == null ? " (automatisch)" : ""));
                }
                return true;
            default:
                help(sender);
                return true;
        }
    }

    private static String describeLoc(Location l) {
        return l == null ? "(niet gezet)" : l.getWorld().getName() + " " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Parkour ===");
        sender.sendMessage(ChatColor.YELLOW + "Stap op de start om te beginnen.");
        sender.sendMessage(ChatColor.YELLOW + "/parkour top" + ChatColor.GRAY + " - snelste tijden");
        sender.sendMessage(ChatColor.YELLOW + "/parkour terug" + ChatColor.GRAY + " - terug naar je laatste checkpoint");
        sender.sendMessage(ChatColor.YELLOW + "/parkour stop" + ChatColor.GRAY + " - stoppen");
        if (sender.hasPermission("lobbygames.admin")) {
            sender.sendMessage(ChatColor.GOLD + "Beheer (op de plek waar je staat):");
            sender.sendMessage(ChatColor.YELLOW + "/parkour setstart | addcheckpoint | setfinish");
            sender.sendMessage(ChatColor.YELLOW + "/parkour clearcheckpoints | setfall [auto] | resettimes | info");
        }
    }

    private void top(CommandSender sender) {
        if (best.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Nog niemand heeft de parkour gehaald.");
            return;
        }
        List<Map.Entry<UUID, Long>> list = new ArrayList<>(best.entrySet());
        list.sort(Map.Entry.comparingByValue());
        sender.sendMessage(ChatColor.GOLD + "=== Snelste parkour-tijden ===");
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            Map.Entry<UUID, Long> e = list.get(i);
            ChatColor color = i == 0 ? ChatColor.GOLD : i == 1 ? ChatColor.GRAY : i == 2 ? ChatColor.RED : ChatColor.WHITE;
            sender.sendMessage(color + "" + (i + 1) + ". " + names.getOrDefault(e.getKey(), "?") + ChatColor.GRAY
                    + " - " + LobbyGames.formatTime(e.getValue()));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> options = new ArrayList<>(Arrays.asList("top", "terug", "stop"));
        if (sender.hasPermission("lobbygames.admin")) {
            options.addAll(Arrays.asList("setstart", "addcheckpoint", "setfinish", "clearcheckpoints", "setfall", "resettimes", "info"));
        }
        List<String> result = new ArrayList<>();
        for (String o : options) if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(o);
        return result;
    }
}
