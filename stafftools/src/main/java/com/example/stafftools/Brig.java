package com.example.stafftools;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * /brig: zet een speler (voor de grap, of om even te praten) op de brig-plek. Hij kan
 * niet lopen, wel rondkijken en chatten; na de timer gaat hij naar de spawn.
 *
 *  /brig set                       - brig-plek = waar je staat
 *  /brig <speler> [tijd] [reden]   - tijd zoals 30s, 5m, 1h (standaard 5m)
 *  /brig vrij <speler>             - eerder vrijlaten
 *  /brig list                      - wie zit er nu
 *
 * De tijd loopt door als hij uitlogt; komt hij terug terwijl de tijd nog loopt, dan staat
 * hij meteen weer in de brig. Zolang hij erin zit kan hij geen schade krijgen of doen
 * (veilig in hardcore), niet bouwen, geen items gooien en geen teleport-commando's gebruiken.
 */
final class Brig implements TabExecutor, Listener {

    private static final String PERMISSION = "stafftools.staff";
    private static final Pattern TIME = Pattern.compile("(\\d+)([smhd]?)");
    private static final Set<String> ALLOWED_COMMANDS = new HashSet<>(Arrays.asList(
            "msg", "tell", "w", "r", "reply", "report", "rules", "team", "minecraft:msg", "minecraft:tell", "minecraft:w"));

    private static final class Stay {
        String name;
        long until;
        String reason;
    }

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private Location brig;
    private final Map<UUID, Stay> jailed = new HashMap<>();
    private final Set<UUID> ownTeleport = new HashSet<>();

    Brig(JavaPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "brig.yml");
        data = YamlConfiguration.loadConfiguration(file);
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    // ---------- Opslag ----------

    private void load() {
        ConfigurationSection loc = data.getConfigurationSection("location");
        if (loc != null) {
            World world = Bukkit.getWorld(loc.getString("world", ""));
            if (world != null) {
                brig = new Location(world, loc.getDouble("x"), loc.getDouble("y"), loc.getDouble("z"),
                        (float) loc.getDouble("yaw"), (float) loc.getDouble("pitch"));
            }
        }
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        for (String key : players.getKeys(false)) {
            try {
                Stay stay = new Stay();
                stay.name = players.getString(key + ".name", "?");
                stay.until = players.getLong(key + ".until");
                stay.reason = players.getString(key + ".reason", "");
                jailed.put(UUID.fromString(key), stay);
            } catch (IllegalArgumentException ignored) {
                // overslaan
            }
        }
    }

    private void save() {
        if (brig != null) {
            data.set("location.world", brig.getWorld().getName());
            data.set("location.x", brig.getX());
            data.set("location.y", brig.getY());
            data.set("location.z", brig.getZ());
            data.set("location.yaw", (double) brig.getYaw());
            data.set("location.pitch", (double) brig.getPitch());
        }
        data.set("players", null);
        for (Map.Entry<UUID, Stay> e : jailed.entrySet()) {
            String path = "players." + e.getKey();
            data.set(path + ".name", e.getValue().name);
            data.set(path + ".until", e.getValue().until);
            data.set(path + ".reason", e.getValue().reason);
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Kon brig.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Commando ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "set": {
                if (!(sender instanceof Player)) return true;
                brig = ((Player) sender).getLocation();
                save();
                sender.sendMessage(ChatColor.GREEN + "Brig-plek gezet (ook de kijkrichting).");
                return true;
            }
            case "list": {
                if (jailed.isEmpty()) {
                    sender.sendMessage(ChatColor.GRAY + "Er zit niemand in de brig.");
                    return true;
                }
                sender.sendMessage(ChatColor.GOLD + "=== Brig ===");
                long now = System.currentTimeMillis();
                for (Stay stay : jailed.values()) {
                    sender.sendMessage(ChatColor.WHITE + stay.name + ChatColor.GRAY
                            + (stay.until <= now ? " - vrij zodra hij inlogt" : " - nog " + format(stay.until - now))
                            + (stay.reason.isEmpty() ? "" : ", reden: " + stay.reason));
                }
                return true;
            }
            case "vrij": {
                if (args.length != 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /brig vrij <speler>");
                    return true;
                }
                UUID uuid = findJailed(args[1]);
                if (uuid == null) {
                    sender.sendMessage(ChatColor.RED + args[1] + " zit niet in de brig.");
                    return true;
                }
                release(uuid, true);
                sender.sendMessage(ChatColor.GREEN + args[1] + " is vrijgelaten.");
                return true;
            }
            default:
                jail(sender, args);
                return true;
        }
    }

    private void jail(CommandSender sender, String[] args) {
        if (brig == null) {
            sender.sendMessage(ChatColor.RED + "Er is nog geen brig-plek. Ga erop staan en typ /brig set.");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Speler '" + args[0] + "' is niet online.");
            return;
        }
        long millis = Duration.ofMinutes(5).toMillis();
        int reasonFrom = 1;
        if (args.length >= 2) {
            Long parsed = parseTime(args[1]);
            if (parsed != null) {
                millis = parsed;
                reasonFrom = 2;
            }
        }
        if (millis < 1000 || millis > Duration.ofDays(7).toMillis()) {
            sender.sendMessage(ChatColor.RED + "Kies een tijd tussen 1 seconde en 7 dagen (bv. 30s, 5m, 1h).");
            return;
        }
        Stay stay = new Stay();
        stay.name = target.getName();
        stay.until = System.currentTimeMillis() + millis;
        stay.reason = String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length));
        jailed.put(target.getUniqueId(), stay);
        save();

        teleport(target, brig);
        target.showTitle(Title.title(legacy("&c&lDE BRIG"), legacy(stay.reason.isEmpty() ? "&7Even nadenken..." : "&7" + stay.reason),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        target.playSound(target.getLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.7f);
        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + target.getName() + ChatColor.RED + " is door "
                + sender.getName() + " in de brig gezet voor " + format(millis)
                + (stay.reason.isEmpty() ? "!" : ": " + ChatColor.WHITE + stay.reason));
        plugin.getLogger().info("[Brig] " + sender.getName() + " zet " + target.getName() + " in de brig voor " + format(millis)
                + (stay.reason.isEmpty() ? "" : " (" + stay.reason + ")") + ".");
    }

    /** Uit de brig; online = meteen naar de spawn, offline = bij het inloggen (tijd op 0, zie onJoin). */
    private void release(UUID uuid, boolean early) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) {
            Stay stay = jailed.get(uuid);
            if (stay != null) stay.until = 0;
            save();
            return;
        }
        Stay stay = jailed.remove(uuid);
        save();
        teleport(player, Bukkit.getWorlds().get(0).getSpawnLocation());
        player.showTitle(Title.title(legacy("&a&lVRIJ!"), legacy(early ? "&7Je bent eerder vrijgelaten." : "&7Je tijd zit erop."),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(700))));
        player.playSound(player.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 1.2f);
        if (stay != null) plugin.getLogger().info("[Brig] " + stay.name + " is weer vrij.");
    }

    private UUID findJailed(String name) {
        for (Map.Entry<UUID, Stay> e : jailed.entrySet()) {
            if (e.getValue().name.equalsIgnoreCase(name)) return e.getKey();
        }
        return null;
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Brig ===");
        sender.sendMessage(ChatColor.YELLOW + "/brig <speler> [tijd] [reden]" + ChatColor.GRAY + " - tijd: 30s, 5m, 1h (standaard 5m)");
        sender.sendMessage(ChatColor.YELLOW + "/brig vrij <speler>" + ChatColor.GRAY + " - eerder vrijlaten");
        sender.sendMessage(ChatColor.YELLOW + "/brig list" + ChatColor.GRAY + " - wie zit er nu");
        sender.sendMessage(ChatColor.YELLOW + "/brig set" + ChatColor.GRAY + " - brig-plek = waar je nu staat"
                + (brig == null ? ChatColor.RED + " (nog niet gezet!)" : ""));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return Collections.emptyList();
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(Arrays.asList("set", "vrij", "list"));
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else if (args.length == 2 && args[0].equalsIgnoreCase("vrij")) {
            for (Stay stay : jailed.values()) options.add(stay.name);
        } else if (args.length == 2 && !Arrays.asList("set", "list").contains(args[0].toLowerCase(Locale.ROOT))) {
            options.addAll(Arrays.asList("30s", "1m", "5m", "10m", "1h"));
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(typed)) result.add(option);
        return result;
    }

    // ---------- Timer ----------

    private void tick() {
        if (jailed.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (UUID uuid : new ArrayList<>(jailed.keySet())) {
            Stay stay = jailed.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player == null) continue; // tijd loopt door; afhandelen bij inloggen
            if (now >= stay.until) {
                release(uuid, false);
                continue;
            }
            player.sendActionBar(legacy("&c&lBrig &7- nog &f" + format(stay.until - now)));
        }
    }

    // ---------- Vast blijven zitten ----------

    private boolean isJailed(Player player) {
        return jailed.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Stay stay = jailed.get(player.getUniqueId());
        if (stay == null) return;
        // Even wachten: de lobby-plugin zet je bij het inloggen ook ergens neer.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            if (System.currentTimeMillis() >= stay.until) release(player.getUniqueId(), false);
            else if (brig != null) teleport(player, brig);
        }, 10L);
    }

    /** Wel rondkijken, niet lopen. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isJailed(event.getPlayer())) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;
        Location stay = from.clone();
        stay.setYaw(to.getYaw());
        stay.setPitch(to.getPitch());
        event.setTo(stay);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (isJailed(event.getPlayer()) && !ownTeleport.contains(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isJailed(player) || player.hasPermission(PERMISSION)) return;
        String cmd = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (ALLOWED_COMMANDS.contains(cmd)) return;
        event.setCancelled(true);
        player.sendMessage(ChatColor.RED + "Je zit in de brig. Je kunt wel chatten, /msg en /report gebruiken.");
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && isJailed((Player) event.getEntity())) event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent
                && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player
                && isJailed((Player) ((EntityDamageByEntityEvent) event).getDamager())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (isJailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (isJailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isJailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isJailed(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onProjectile(ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player && isJailed((Player) event.getEntity().getShooter())) {
            event.setCancelled(true);
        }
    }

    // ---------- Hulpjes ----------

    private void teleport(Player player, Location to) {
        ownTeleport.add(player.getUniqueId());
        try {
            player.teleport(to);
        } finally {
            ownTeleport.remove(player.getUniqueId());
        }
    }

    /** "30s", "5m", "1h", "2d"; zonder letter = minuten. */
    static Long parseTime(String text) {
        Matcher m = TIME.matcher(text.toLowerCase(Locale.ROOT));
        if (!m.matches()) return null;
        long n = Long.parseLong(m.group(1));
        switch (m.group(2)) {
            case "s": return Duration.ofSeconds(n).toMillis();
            case "h": return Duration.ofHours(n).toMillis();
            case "d": return Duration.ofDays(n).toMillis();
            default: return Duration.ofMinutes(n).toMillis();
        }
    }

    static String format(long millis) {
        long s = Math.max(0, (millis + 999) / 1000);
        if (s >= 3600) return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    private static net.kyori.adventure.text.Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
