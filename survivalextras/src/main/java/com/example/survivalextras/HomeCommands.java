package com.example.survivalextras;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * /sethome, /home, /delhome en /homes. Maximaal 'max-homes' per speler
 * (standaard 2), met wachttijd en cooldown. Opgeslagen in homes.yml.
 */
final class HomeCommands implements TabExecutor {

    private static final String DEFAULT_NAME = "home";

    private final JavaPlugin plugin;
    private final Teleports teleports;
    private final int maxHomes;
    private final int warmupSeconds;
    private final int cooldownSeconds;
    private final Set<String> blockedWorlds = new HashSet<>();

    private final File file;
    private final YamlConfiguration data;
    private final Map<UUID, Long> lastHome = new HashMap<>();

    HomeCommands(JavaPlugin plugin, Teleports teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        maxHomes = Math.max(1, plugin.getConfig().getInt("homes.max-homes", 2));
        warmupSeconds = plugin.getConfig().getInt("homes.warmup-seconds", 5);
        cooldownSeconds = plugin.getConfig().getInt("homes.cooldown-seconds", 30);
        for (String world : plugin.getConfig().getStringList("homes.blocked-worlds")) {
            blockedWorlds.add(world.toLowerCase(Locale.ROOT));
        }

        file = new File(plugin.getDataFolder(), "homes.yml");
        data = YamlConfiguration.loadConfiguration(file);
    }

    void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Kon homes.yml niet opslaan: " + e.getMessage());
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "sethome": setHome(player, args); break;
            case "home": home(player, args); break;
            case "delhome": delHome(player, args); break;
            case "homes": list(player); break;
            default: return false;
        }
        return true;
    }

    // ---------- Commando's ----------

    private void setHome(Player player, String[] args) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(ChatColor.RED + "Als toeschouwer kun je geen home zetten.");
            return;
        }
        if (isBlockedWorld(player.getWorld())) {
            player.sendMessage(ChatColor.RED + "In deze wereld kun je geen home zetten.");
            return;
        }
        String name = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : DEFAULT_NAME;
        if (!name.matches("[a-z0-9_-]{1,16}")) {
            player.sendMessage(ChatColor.RED + "Gebruik voor de naam alleen letters, cijfers, - en _ (max. 16 tekens).");
            return;
        }

        List<String> homes = names(player.getUniqueId());
        boolean replacing = homes.contains(name);
        if (!replacing && homes.size() >= maxHomes) {
            player.sendMessage(ChatColor.RED + "Je hebt al " + maxHomes + " homes (" + String.join(", ", homes)
                    + "). Verwijder er eerst een met /delhome <naam>, of gebruik dezelfde naam om hem te verplaatsen.");
            return;
        }

        Location loc = player.getLocation();
        String path = path(player.getUniqueId(), name);
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
        data.set(path + ".yaw", (double) loc.getYaw());
        data.set(path + ".pitch", (double) loc.getPitch());
        data.set("homes." + player.getUniqueId() + ".last-name", player.getName());
        save();

        int count = replacing ? homes.size() : homes.size() + 1;
        player.sendMessage(ChatColor.GREEN + "Home '" + name + "' " + (replacing ? "verplaatst" : "opgeslagen")
                + " (" + count + "/" + maxHomes + "). Ga erheen met " + ChatColor.WHITE + "/home"
                + (name.equals(DEFAULT_NAME) ? "" : " " + name) + ChatColor.GREEN + ".");
        player.playSound(loc, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.8f, 1.4f);
    }

    private void home(Player player, String[] args) {
        if (player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(ChatColor.RED + "Als toeschouwer kun je /home niet gebruiken.");
            return;
        }
        List<String> homes = names(player.getUniqueId());
        if (homes.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Je hebt nog geen home. Zet er een met /sethome [naam].");
            return;
        }

        String name;
        if (args.length > 0) {
            name = args[0].toLowerCase(Locale.ROOT);
            if (!homes.contains(name)) {
                player.sendMessage(ChatColor.RED + "Je hebt geen home met de naam '" + name + "'.");
                list(player);
                return;
            }
        } else if (homes.size() == 1) {
            name = homes.get(0);
        } else if (homes.contains(DEFAULT_NAME)) {
            name = DEFAULT_NAME;
        } else {
            list(player);
            return;
        }

        long wait = cooldownLeft(player);
        if (wait > 0) {
            player.sendMessage(ChatColor.RED + "Je moet nog " + wait + " seconde(n) wachten voor je weer /home kunt gebruiken.");
            return;
        }

        UUID uuid = player.getUniqueId();
        teleports.teleport(player, "home '" + name + "'", warmupSeconds, () -> {
            Location loc = load(uuid, name);
            if (loc == null) player.sendMessage(ChatColor.RED + "Die home bestaat niet meer, of de wereld is niet geladen.");
            return loc;
        }, () -> lastHome.put(uuid, System.currentTimeMillis()));
    }

    private void delHome(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Gebruik: /delhome <naam>");
            return;
        }
        String name = args[0].toLowerCase(Locale.ROOT);
        if (!names(player.getUniqueId()).contains(name)) {
            player.sendMessage(ChatColor.RED + "Je hebt geen home met de naam '" + name + "'.");
            return;
        }
        data.set(path(player.getUniqueId(), name), null);
        save();
        player.sendMessage(ChatColor.GREEN + "Home '" + name + "' verwijderd.");
    }

    private void list(Player player) {
        List<String> homes = names(player.getUniqueId());
        if (homes.isEmpty()) {
            player.sendMessage(ChatColor.RED + "Je hebt nog geen home. Zet er een met /sethome [naam].");
            return;
        }
        TextComponent.Builder line = Component.text()
                .append(Component.text("Je homes (" + homes.size() + "/" + maxHomes + "): ", NamedTextColor.GOLD));
        for (int i = 0; i < homes.size(); i++) {
            String name = homes.get(i);
            if (i > 0) line.append(Component.text(", ", NamedTextColor.GRAY));
            line.append(Component.text(name, NamedTextColor.YELLOW)
                    .clickEvent(ClickEvent.runCommand("/home " + name))
                    .hoverEvent(HoverEvent.showText(Component.text("Klik om te teleporteren\n" + describe(player.getUniqueId(), name), NamedTextColor.GRAY))));
        }
        player.sendMessage(line.build());
        player.sendMessage(ChatColor.GRAY + "Klik op een naam, of gebruik /home <naam>.");
    }

    // ---------- Opslag ----------

    private static String path(UUID uuid, String name) {
        return "homes." + uuid + ".list." + name;
    }

    private List<String> names(UUID uuid) {
        ConfigurationSection section = data.getConfigurationSection("homes." + uuid + ".list");
        if (section == null) return Collections.emptyList();
        List<String> names = new ArrayList<>(section.getKeys(false));
        Collections.sort(names);
        return names;
    }

    private Location load(UUID uuid, String name) {
        ConfigurationSection s = data.getConfigurationSection(path(uuid, name));
        if (s == null) return null;
        World world = Bukkit.getWorld(s.getString("world", ""));
        if (world == null) return null;
        return new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private String describe(UUID uuid, String name) {
        ConfigurationSection s = data.getConfigurationSection(path(uuid, name));
        if (s == null) return "";
        return s.getString("world", "?") + ": " + (int) Math.floor(s.getDouble("x")) + ", "
                + (int) Math.floor(s.getDouble("y")) + ", " + (int) Math.floor(s.getDouble("z"));
    }

    // ---------- Hulpjes ----------

    /** Werelden uit de config, plus de lobby van LobbySpawn als dat een aparte wereld is. */
    private boolean isBlockedWorld(World world) {
        if (blockedWorlds.contains(world.getName().toLowerCase(Locale.ROOT))) return true;
        Plugin lobby = Bukkit.getPluginManager().getPlugin("LobbySpawn");
        if (lobby == null || !lobby.getConfig().getBoolean("lobby.set", false)) return false;
        String lobbyWorld = lobby.getConfig().getString("lobby.world", "");
        String mainWorld = Bukkit.getWorlds().get(0).getName();
        return !lobbyWorld.equals(mainWorld) && world.getName().equals(lobbyWorld);
    }

    private long cooldownLeft(Player player) {
        if (cooldownSeconds <= 0 || Teleports.bypass(player)) return 0;
        Long last = lastHome.get(player.getUniqueId());
        if (last == null) return 0;
        return Math.max(0, cooldownSeconds - (System.currentTimeMillis() - last) / 1000L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || args.length != 1) return Collections.emptyList();
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!name.equals("home") && !name.equals("delhome") && !name.equals("sethome")) return Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String home : names(((Player) sender).getUniqueId())) {
            if (home.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(home);
        }
        return result;
    }
}
