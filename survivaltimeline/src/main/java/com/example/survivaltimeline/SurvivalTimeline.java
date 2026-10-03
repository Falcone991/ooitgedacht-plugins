package com.example.survivaltimeline;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class SurvivalTimeline extends JavaPlugin implements Listener {

    private File dataFile;
    private FileConfiguration data;

    // Instellingen (uit config.yml)
    private int weeksBeforeHardcore;
    private int netherOpenWeek;
    private int endOpenWeek;
    private double borderIncrementPerDay;
    private double borderStartSize;
    private String mainWorldName;
    private boolean announce;

    // Status (uit data.yml, blijft bewaard na herstart)
    private long startTime;
    private boolean hardcoreActivated;
    private boolean netherUnlocked;
    private boolean endUnlocked;
    private long lastCountedDay;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);

        // Elke seconde controleren (20 ticks). Vaak genoeg voor "weken" en
        // "in-game dagen", en licht genoeg om de server niet te belasten.
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        getLogger().info("SurvivalTimeline actief. Gestart op: " + new java.util.Date(startTime));
        getLogger().info("Hardcore na " + weeksBeforeHardcore + " volledige week(en), Nether open vanaf week "
                + netherOpenWeek + ", End open vanaf week " + endOpenWeek + ".");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        FileConfiguration config = getConfig();
        weeksBeforeHardcore = config.getInt("weeks-before-hardcore", 3);
        netherOpenWeek = config.getInt("nether-open-week", 2);
        endOpenWeek = config.getInt("end-open-week", 3);
        borderIncrementPerDay = config.getDouble("border-increment-per-ingame-day", 2.0);
        borderStartSize = config.getDouble("border-start-size", 200.0);
        announce = config.getBoolean("announce", true);

        String configuredWorld = config.getString("main-world", "");
        if (configuredWorld == null || configuredWorld.isEmpty()) {
            mainWorldName = Bukkit.getWorlds().get(0).getName();
        } else {
            mainWorldName = configuredWorld;
        }
    }

    private void loadData() {
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Kon data.yml niet aanmaken: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        if (!data.contains("start-time")) {
            // Allereerste keer opstarten: nu is het startpunt van de timeline.
            startTime = System.currentTimeMillis();
        } else {
            startTime = data.getLong("start-time");
        }

        hardcoreActivated = data.getBoolean("hardcore-activated", false);
        netherUnlocked = data.getBoolean("nether-unlocked", false);
        endUnlocked = data.getBoolean("end-unlocked", false);
        lastCountedDay = data.getLong("last-counted-day", -1);

        // Wereldgrens instellen bij allereerste start, als hij nog niet bestaat.
        if (!data.contains("border-initialized")) {
            World world = Bukkit.getWorld(mainWorldName);
            if (world != null) {
                world.getWorldBorder().setSize(borderStartSize);
            }
            data.set("border-initialized", true);
        }

        saveData();
    }

    private void saveData() {
        try {
            data.set("start-time", startTime);
            data.set("hardcore-activated", hardcoreActivated);
            data.set("nether-unlocked", netherUnlocked);
            data.set("end-unlocked", endUnlocked);
            data.set("last-counted-day", lastCountedDay);
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Kon data.yml niet opslaan: " + e.getMessage());
        }
    }

    private void tick() {
        long elapsedMillis = System.currentTimeMillis() - startTime;
        long elapsedWeeks = TimeUnit.MILLISECONDS.toDays(elapsedMillis) / 7;

        // "Week N" = het begin van de N-de week. Week 1 loopt van dag 0 t/m 6,
        // week 2 begint op dag 7, week 3 op dag 14, enzovoort.
        long currentWeek = elapsedWeeks + 1;

        checkHardcore(elapsedWeeks);
        checkNether(currentWeek);
        checkEnd(currentWeek);
        checkWorldBorder();
    }

    private void checkHardcore(long elapsedWeeks) {
        if (hardcoreActivated || elapsedWeeks < weeksBeforeHardcore) return;

        hardcoreActivated = true;
        saveData();

        for (World w : Bukkit.getWorlds()) {
            w.setHardcore(true);
        }

        if (announce) {
            Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD
                    + "De genadeperiode is voorbij! De server is nu HARDCORE — sterven is definitief.");
        }
        getLogger().info("Hardcore geactiveerd na " + elapsedWeeks + " week(en).");
    }

    private void checkNether(long currentWeek) {
        if (netherUnlocked || currentWeek < netherOpenWeek) return;

        netherUnlocked = true;
        saveData();

        if (announce) {
            Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "De Nether is nu open!");
        }
        getLogger().info("Nether ontgrendeld (week " + currentWeek + ").");
    }

    private void checkEnd(long currentWeek) {
        if (endUnlocked || currentWeek < endOpenWeek) return;

        endUnlocked = true;
        saveData();

        if (announce) {
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "" + ChatColor.BOLD + "Het End is nu open!");
        }
        getLogger().info("End ontgrendeld (week " + currentWeek + ").");
    }

    private void checkWorldBorder() {
        World world = Bukkit.getWorld(mainWorldName);
        if (world == null) return;

        long currentDay = world.getFullTime() / 24000L;

        if (lastCountedDay < 0) {
            lastCountedDay = currentDay;
            saveData();
            return;
        }

        if (currentDay > lastCountedDay) {
            long daysPassed = currentDay - lastCountedDay;
            WorldBorder border = world.getWorldBorder();
            double increase = borderIncrementPerDay * daysPassed;
            double newSize = border.getSize() + increase;

            // Rustig laten "groeien" over 60 seconden i.p.v. abrupt springen.
            border.setSize(newSize, 60L);

            lastCountedDay = currentDay;
            saveData();

            if (announce) {
                Bukkit.broadcastMessage(ChatColor.AQUA + "De wereldgrens groeit met "
                        + formatNumber(increase) + " blokken! Nieuwe grootte: " + formatNumber(newSize));
            }
        }
    }

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) return;

        World.Environment destination = event.getTo().getWorld().getEnvironment();

        if (destination == World.Environment.NETHER && !netherUnlocked) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "De Nether is nog gesloten. Kom terug vanaf week " + netherOpenWeek + ".");
        } else if (destination == World.Environment.THE_END && !endUnlocked) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED
                    + "Het End is nog gesloten. Kom terug vanaf week " + endOpenWeek + ".");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("survivaltimeline")) return false;

        long elapsedMillis = System.currentTimeMillis() - startTime;
        long elapsedDays = TimeUnit.MILLISECONDS.toDays(elapsedMillis);
        long elapsedWeeks = elapsedDays / 7;
        long dayOfWeek = elapsedDays % 7;

        World world = Bukkit.getWorld(mainWorldName);
        double borderSize = world != null ? world.getWorldBorder().getSize() : -1;

        sender.sendMessage(ChatColor.YELLOW + "=== SurvivalTimeline status ===");
        sender.sendMessage(ChatColor.GRAY + "Verstreken tijd: " + ChatColor.WHITE
                + elapsedWeeks + " week(en), " + dayOfWeek + " dag(en)"
                + ChatColor.GRAY + " (je zit in week " + (elapsedWeeks + 1) + ")");
        sender.sendMessage(ChatColor.GRAY + "Hardcore: " + (hardcoreActivated
                ? ChatColor.RED + "actief"
                : ChatColor.GREEN + "nog niet (na " + weeksBeforeHardcore + " volledige weken)"));
        sender.sendMessage(ChatColor.GRAY + "Nether: " + (netherUnlocked
                ? ChatColor.GREEN + "open"
                : ChatColor.RED + "gesloten (open vanaf week " + netherOpenWeek + ")"));
        sender.sendMessage(ChatColor.GRAY + "End: " + (endUnlocked
                ? ChatColor.GREEN + "open"
                : ChatColor.RED + "gesloten (open vanaf week " + endOpenWeek + ")"));
        if (world != null) {
            sender.sendMessage(ChatColor.GRAY + "Wereldgrens (" + world.getName() + "): "
                    + ChatColor.WHITE + formatNumber(borderSize) + " blokken");
        }
        return true;
    }

    private String formatNumber(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }
}
