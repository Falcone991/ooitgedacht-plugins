package com.example.lobbygames;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Launchpads: drukplaten die beheer aanwijst schieten je de lucht in, vooruit in de
 * richting die beheer keek bij het neerzetten (of, met "vrij", de kant die jij op kijkt).
 * Alleen aangewezen platen doen iets; een gewone drukplaat blijft gewoon een drukplaat.
 * Na een lancering geen valschade.
 */
final class LaunchPads implements TabExecutor, Listener {

    private static final class Pad {
        String world;
        int x, y, z;
        double forward;
        double up;
        Vector direction; // null = vrij: de kant die de speler op kijkt
    }

    private static final long COOLDOWN_MS = 1000L;
    private static final long NO_FALL_DAMAGE_MS = 10_000L;

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Map<String, Pad> pads = new LinkedHashMap<>();
    private final Map<UUID, Long> lastLaunch = new HashMap<>();

    LaunchPads(JavaPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "launchpads.yml");
        data = YamlConfiguration.loadConfiguration(file);
        load();
    }

    String describe() {
        return pads.size() + " launchpad(s)";
    }

    private static String key(String world, int x, int y, int z) {
        return world + ":" + x + ":" + y + ":" + z;
    }

    private static String key(Block block) {
        return key(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    // ---------- Opslag ----------

    private void load() {
        ConfigurationSection section = data.getConfigurationSection("pads");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(id);
            if (s == null) continue;
            Pad pad = new Pad();
            pad.world = s.getString("world", "");
            pad.x = s.getInt("x");
            pad.y = s.getInt("y");
            pad.z = s.getInt("z");
            pad.forward = s.getDouble("forward", 2.0);
            pad.up = s.getDouble("up", 1.0);
            pad.direction = s.getBoolean("free", false) ? null : new Vector(s.getDouble("dx"), 0, s.getDouble("dz"));
            pads.put(key(pad.world, pad.x, pad.y, pad.z), pad);
        }
    }

    private void save() {
        data.set("pads", null);
        int i = 0;
        for (Pad pad : pads.values()) {
            String path = "pads." + i++;
            data.set(path + ".world", pad.world);
            data.set(path + ".x", pad.x);
            data.set(path + ".y", pad.y);
            data.set(path + ".z", pad.z);
            data.set(path + ".forward", pad.forward);
            data.set(path + ".up", pad.up);
            data.set(path + ".free", pad.direction == null);
            if (pad.direction != null) {
                data.set(path + ".dx", pad.direction.getX());
                data.set(path + ".dz", pad.direction.getZ());
            }
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Kon launchpads.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Lanceren ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (pads.isEmpty()) return;
        Location to = event.getTo();
        Pad pad = pads.get(key(to.getBlock()));
        if (pad == null) return;

        Player player = event.getPlayer();
        long now = System.currentTimeMillis();
        Long last = lastLaunch.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return;
        lastLaunch.put(player.getUniqueId(), now);

        Vector dir = pad.direction != null ? pad.direction.clone() : player.getLocation().getDirection().setY(0);
        if (dir.lengthSquared() < 1.0E-4) dir = new Vector(0, 0, 0);
        else dir.normalize();
        player.setVelocity(dir.multiply(pad.forward).setY(pad.up));

        player.getWorld().playSound(to, Sound.ENTITY_BAT_TAKEOFF, 1.0f, 0.8f);
        player.getWorld().playSound(to, Sound.ENTITY_FIREWORK_ROCKET_LAUNCH, 0.7f, 1.2f);
        player.getWorld().spawnParticle(Particle.CLOUD, to.clone().add(0, 0.2, 0), 15, 0.3, 0.1, 0.3, 0.05);
    }

    /** Geen valschade kort na een lancering. */
    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL || !(event.getEntity() instanceof Player)) return;
        Long last = lastLaunch.get(event.getEntity().getUniqueId());
        if (last != null && System.currentTimeMillis() - last < NO_FALL_DAMAGE_MS) event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastLaunch.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Commando ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lobbygames.admin")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("list")) {
            list(sender);
            return true;
        }
        if (!(sender instanceof Player) || !(sub.equals("set") || sub.equals("remove"))) {
            help(sender);
            return true;
        }
        Player p = (Player) sender;
        Block block = p.getLocation().getBlock();

        if (sub.equals("remove")) {
            if (pads.remove(key(block)) == null) {
                p.sendMessage(ChatColor.RED + "Je staat niet op een launchpad. Ga erop staan en probeer opnieuw.");
                return true;
            }
            save();
            p.sendMessage(ChatColor.GREEN + "Launchpad weggehaald.");
            return true;
        }

        // set [vooruit] [omhoog] [vrij]
        if (!block.getType().name().endsWith("_PRESSURE_PLATE")) {
            p.sendMessage(ChatColor.RED + "Ga op een drukplaat staan om er een launchpad van te maken.");
            return true;
        }
        Pad pad = new Pad();
        pad.world = block.getWorld().getName();
        pad.x = block.getX();
        pad.y = block.getY();
        pad.z = block.getZ();
        pad.forward = 2.0;
        pad.up = 1.0;
        boolean free = false;
        List<Double> numbers = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].equalsIgnoreCase("vrij")) {
                free = true;
                continue;
            }
            try {
                numbers.add(Double.parseDouble(args[i].replace(',', '.')));
            } catch (NumberFormatException e) {
                p.sendMessage(ChatColor.YELLOW + "Gebruik: /launchpad set [vooruit] [omhoog] [vrij]  (bv. /launchpad set 3 1.2)");
                return true;
            }
        }
        if (numbers.size() > 0) pad.forward = numbers.get(0);
        if (numbers.size() > 1) pad.up = numbers.get(1);
        if (pad.forward < 0 || pad.forward > 10 || pad.up < 0 || pad.up > 5) {
            p.sendMessage(ChatColor.RED + "Vooruit kan 0 t/m 10 zijn, omhoog 0 t/m 5.");
            return true;
        }
        pad.direction = free ? null : p.getLocation().getDirection().setY(0);
        if (pad.direction != null && pad.direction.lengthSquared() < 1.0E-4) {
            p.sendMessage(ChatColor.RED + "Kijk recht vooruit (niet recht omhoog of omlaag) zodat de richting duidelijk is.");
            return true;
        }
        if (pad.direction != null) pad.direction.normalize();
        pads.put(key(block), pad);
        save();
        p.sendMessage(ChatColor.GREEN + "Launchpad gezet: vooruit " + pad.forward + ", omhoog " + pad.up
                + (free ? ", richting: waar de speler heen kijkt." : ", richting: waar jij nu heen kijkt.")
                + ChatColor.GRAY + " Stap eraf en weer op om te testen.");
        return true;
    }

    private void list(CommandSender sender) {
        if (pads.isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + "Er zijn nog geen launchpads. Ga op een drukplaat staan en typ /launchpad set.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "=== Launchpads (" + pads.size() + ") ===");
        for (Pad pad : pads.values()) {
            sender.sendMessage(ChatColor.WHITE + pad.world + " " + pad.x + ", " + pad.y + ", " + pad.z
                    + ChatColor.GRAY + " - vooruit " + pad.forward + ", omhoog " + pad.up + (pad.direction == null ? ", vrij" : ""));
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Launchpads ===");
        sender.sendMessage(ChatColor.YELLOW + "/launchpad set [vooruit] [omhoog] [vrij]" + ChatColor.GRAY
                + " - drukplaat waar je op staat; richting = waar je heen kijkt (standaard 2 en 1)");
        sender.sendMessage(ChatColor.YELLOW + "/launchpad remove" + ChatColor.GRAY + " - launchpad waar je op staat weghalen");
        sender.sendMessage(ChatColor.YELLOW + "/launchpad list" + ChatColor.GRAY + " - alle launchpads");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("lobbygames.admin")) return Collections.emptyList();
        List<String> options;
        if (args.length == 1) options = Arrays.asList("set", "remove", "list");
        else if (args[0].equalsIgnoreCase("set") && args.length <= 4) options = Arrays.asList("vrij");
        else return Collections.emptyList();
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.startsWith(typed)) result.add(option);
        return result;
    }
}
