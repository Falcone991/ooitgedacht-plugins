package com.example.lobbygames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Spelletjes in de lobby:
 *  - Parkour: start, checkpoints en finish die beheer aanwijst; timer, beste tijden,
 *    vallen = terug naar je checkpoint.
 *  - Duels: 1-tegen-1 in een arena, met een vaste kit. Je eigen spullen worden zolang
 *    opgeborgen en je gaat nooit echt dood (belangrijk als het hardcore is).
 *  - Launchpads: aangewezen drukplaten schieten je vooruit de lucht in.
 */
public class LobbyGames extends JavaPlugin {

    private Duels duels;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        Parkour parkour = new Parkour(this);
        register("parkour", parkour);
        Bukkit.getPluginManager().registerEvents(parkour, this);
        long timerTicks = Math.max(1, getConfig().getLong("parkour.timer-ticks", 2));
        Bukkit.getScheduler().runTaskTimer(this, parkour::showTimers, timerTicks, timerTicks);

        duels = new Duels(this);
        register("duel", duels);
        Bukkit.getPluginManager().registerEvents(duels, this);

        LaunchPads launchPads = new LaunchPads(this);
        register("launchpad", launchPads);
        Bukkit.getPluginManager().registerEvents(launchPads, this);

        getLogger().info("LobbyGames geladen. Parkour: " + parkour.describe() + ". Duels: " + duels.describe()
                + ". Launchpads: " + launchPads.describe() + ".");
    }

    @Override
    public void onDisable() {
        if (duels != null) duels.shutdown();
    }

    private void register(String name, TabExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command == null) return;
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    // ---------- Locaties opslaan ----------

    static void saveLocation(ConfigurationSection parent, String key, Location loc) {
        if (loc == null) {
            parent.set(key, null);
            return;
        }
        ConfigurationSection s = parent.createSection(key);
        s.set("world", loc.getWorld().getName());
        s.set("x", loc.getX());
        s.set("y", loc.getY());
        s.set("z", loc.getZ());
        s.set("yaw", (double) loc.getYaw());
        s.set("pitch", (double) loc.getPitch());
    }

    static Location loadLocation(ConfigurationSection s) {
        if (s == null) return null;
        World world = Bukkit.getWorld(s.getString("world", ""));
        if (world == null) return null;
        return new Location(world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    /** Midden van het blok waar je staat, met je kijkrichting. */
    static Location blockCenter(Location loc) {
        return new Location(loc.getWorld(), loc.getBlockX() + 0.5, loc.getY(), loc.getBlockZ() + 0.5, loc.getYaw(), 0);
    }

    static String formatTime(long ms) {
        long minutes = ms / 60000;
        double seconds = (ms % 60000) / 1000.0;
        return minutes > 0 ? String.format(java.util.Locale.ROOT, "%d:%05.2f", minutes, seconds)
                : String.format(java.util.Locale.ROOT, "%.2f s", seconds);
    }
}
