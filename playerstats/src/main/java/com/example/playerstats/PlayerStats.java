package com.example.playerstats;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PlayerStats extends JavaPlugin implements CommandExecutor, Listener {

    private static final Set<Material> ORES = EnumSet.of(
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE, Material.NETHER_GOLD_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.NETHER_QUARTZ_ORE, Material.ANCIENT_DEBRIS
    );

    // Config
    private boolean scoreboardEnabledByDefault;
    private String scoreboardTitle;
    private List<String> scoreboardLines;
    private long updateIntervalTicks;
    private int xpPerLevel;
    private int xpRewardPerLevel;
    private int miningPerOre;
    private int farmingPerCrop;
    private int fishingPerCatch;
    private int exploringPerBlocks;

    // Level-up effecten (uit config.yml)
    private String levelUpTitle;
    private String levelUpSubtitle;
    private Sound levelUpSound;
    private float levelUpSoundVolume;
    private float levelUpSoundPitch;
    private Particle levelUpParticle;
    private int levelUpParticleCount;

    // Persistente data
    private File dataFile;
    private FileConfiguration data;

    // Transiente voortgang (niet opgeslagen): losse blokken die nog niet
    // hebben opgeteld tot een heel "exploring-per-blocks" punt.
    private final Map<UUID, Double> explorationProgress = new HashMap<>();
    private final Set<UUID> scoreboardShown = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadData();

        getCommand("scoreboard").setExecutor(this);
        getCommand("levels").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        Bukkit.getScheduler().runTaskTimer(this, this::updateAllScoreboards, updateIntervalTicks, updateIntervalTicks);

        getLogger().info("PlayerStats geladen.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();
        scoreboardEnabledByDefault = c.getBoolean("scoreboard.enabled-by-default", true);
        scoreboardTitle = c.getString("scoreboard.title", "&6&lServer Stats");
        updateIntervalTicks = c.getLong("scoreboard.update-interval-ticks", 40L);

        scoreboardLines = c.getStringList("scoreboard.lines");
        if (scoreboardLines == null || scoreboardLines.isEmpty()) {
            scoreboardLines = java.util.Arrays.asList(
                    "&7Speeltijd: &f{hours}u {minutes}m",
                    "&7Doden: &f{deaths}",
                    "&7Level: &f{level}"
            );
        }

        xpPerLevel = c.getInt("leveling.xp-per-level", 100);
        xpRewardPerLevel = c.getInt("leveling.xp-reward-per-level", 25);
        miningPerOre = c.getInt("leveling.points.mining-per-ore", 1);
        farmingPerCrop = c.getInt("leveling.points.farming-per-crop", 1);
        fishingPerCatch = c.getInt("leveling.points.fishing-per-catch", 1);
        exploringPerBlocks = c.getInt("leveling.points.exploring-per-blocks", 50);

        levelUpTitle = c.getString("leveling.level-up.title", "&a&lLEVEL UP!");
        levelUpSubtitle = c.getString("leveling.level-up.subtitle", "&f{category} level {level}");
        levelUpSound = parseSound(c.getString("leveling.level-up.sound", "ENTITY_PLAYER_LEVELUP"));
        levelUpSoundVolume = (float) c.getDouble("leveling.level-up.sound-volume", 1.0);
        levelUpSoundPitch = (float) c.getDouble("leveling.level-up.sound-pitch", 1.0);
        levelUpParticle = parseParticle(c.getString("leveling.level-up.particle", "TOTEM_OF_UNDYING"));
        levelUpParticleCount = c.getInt("leveling.level-up.particle-count", 30);
    }

    private Sound parseSound(String name) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Onbekend geluid in config.yml: '" + name + "', val terug op ENTITY_PLAYER_LEVELUP.");
            return Sound.ENTITY_PLAYER_LEVELUP;
        }
    }

    private Particle parseParticle(String name) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Onbekend deeltje in config.yml: '" + name + "', val terug op TOTEM_OF_UNDYING.");
            return Particle.TOTEM_OF_UNDYING;
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
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Kon data.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Per-speler data helpers ----------

    private boolean isScoreboardEnabled(UUID uuid) {
        return data.getBoolean("players." + uuid + ".scoreboard-enabled", scoreboardEnabledByDefault);
    }

    private void setScoreboardEnabled(UUID uuid, boolean enabled) {
        data.set("players." + uuid + ".scoreboard-enabled", enabled);
        saveData();
    }

    private int getCategoryXp(UUID uuid, String category) {
        return data.getInt("players." + uuid + "." + category + "-xp", 0);
    }

    private void addCategoryXp(Player player, String category, int amount, String displayName) {
        UUID uuid = player.getUniqueId();
        int oldXp = getCategoryXp(uuid, category);
        int newXp = oldXp + amount;
        data.set("players." + uuid + "." + category + "-xp", newXp);
        saveData();

        int oldLevel = oldXp / xpPerLevel;
        int newLevel = newXp / xpPerLevel;
        if (newLevel > oldLevel) {
            player.giveExp(xpRewardPerLevel * (newLevel - oldLevel));
            playLevelUpEffects(player, displayName, newLevel);
        }
    }

    private void playLevelUpEffects(Player player, String categoryDisplayName, int newLevel) {
        String title = ChatColor.translateAlternateColorCodes('&', levelUpTitle);
        String subtitle = ChatColor.translateAlternateColorCodes('&',
                levelUpSubtitle.replace("{category}", categoryDisplayName)
                        .replace("{level}", String.valueOf(newLevel)));

        player.sendTitle(title, subtitle, 10, 50, 20);
        player.playSound(player.getLocation(), levelUpSound, levelUpSoundVolume, levelUpSoundPitch);
        player.getWorld().spawnParticle(levelUpParticle, player.getLocation().add(0, 1, 0), levelUpParticleCount, 0.5, 0.5, 0.5, 0.01);

        player.sendMessage(ChatColor.GREEN + "" + ChatColor.BOLD + categoryDisplayName + " level omhoog! "
                + ChatColor.GREEN + "Je bent nu level " + newLevel + " (+" + xpRewardPerLevel + " XP).");
    }

    private int getLevel(UUID uuid, String category) {
        return getCategoryXp(uuid, category) / xpPerLevel;
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;

        switch (command.getName().toLowerCase()) {
            case "scoreboard":
                return handleScoreboardToggle(player);
            case "levels":
                return handleLevels(player);
            default:
                return false;
        }
    }

    private boolean handleScoreboardToggle(Player player) {
        UUID uuid = player.getUniqueId();
        boolean nowEnabled = !isScoreboardEnabled(uuid);
        setScoreboardEnabled(uuid, nowEnabled);

        if (nowEnabled) {
            scoreboardShown.add(uuid);
            updateScoreboard(player);
            player.sendMessage(ChatColor.GREEN + "Scorebord aangezet.");
        } else {
            scoreboardShown.remove(uuid);
            clearScoreboard(player);
            player.sendMessage(ChatColor.YELLOW + "Scorebord uitgezet.");
        }
        return true;
    }

    private boolean handleLevels(Player player) {
        UUID uuid = player.getUniqueId();

        player.sendMessage(ChatColor.YELLOW + "=== Jouw voortgang ===");
        player.sendMessage(ChatColor.GRAY + "Mijnen: " + ChatColor.WHITE + "level " + getLevel(uuid, "mining")
                + ChatColor.GRAY + " (" + getCategoryXp(uuid, "mining") + " xp)");
        player.sendMessage(ChatColor.GRAY + "Boeren: " + ChatColor.WHITE + "level " + getLevel(uuid, "farming")
                + ChatColor.GRAY + " (" + getCategoryXp(uuid, "farming") + " xp)");
        player.sendMessage(ChatColor.GRAY + "Vissen: " + ChatColor.WHITE + "level " + getLevel(uuid, "fishing")
                + ChatColor.GRAY + " (" + getCategoryXp(uuid, "fishing") + " xp)");
        player.sendMessage(ChatColor.GRAY + "Verkennen: " + ChatColor.WHITE + "level " + getLevel(uuid, "exploring")
                + ChatColor.GRAY + " (" + getCategoryXp(uuid, "exploring") + " xp)");
        return true;
    }

    // ---------- Scorebord ----------

    private int getTotalLevel(UUID uuid) {
        return getLevel(uuid, "mining") + getLevel(uuid, "farming")
                + getLevel(uuid, "fishing") + getLevel(uuid, "exploring");
    }

    private void updateAllScoreboards() {
        for (UUID uuid : scoreboardShown) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                updateScoreboard(player);
            }
        }
    }

    private void updateScoreboard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) return;

        Scoreboard board = manager.getNewScoreboard();
        Objective objective = board.registerNewObjective("stats", "dummy",
                ChatColor.translateAlternateColorCodes('&', scoreboardTitle));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        long playedTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE);
        long totalMinutes = playedTicks / 20 / 60;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;

        int deaths = player.getStatistic(Statistic.DEATHS);
        UUID uuid = player.getUniqueId();
        int totalLevel = getTotalLevel(uuid);

        ChatColor[] colors = ChatColor.values();
        int score = scoreboardLines.size();
        for (int i = 0; i < scoreboardLines.size(); i++) {
            String rawLine = scoreboardLines.get(i)
                    .replace("{hours}", String.valueOf(hours))
                    .replace("{minutes}", String.valueOf(minutes))
                    .replace("{deaths}", String.valueOf(deaths))
                    .replace("{level}", String.valueOf(totalLevel))
                    .replace("{mining_level}", String.valueOf(getLevel(uuid, "mining")))
                    .replace("{farming_level}", String.valueOf(getLevel(uuid, "farming")))
                    .replace("{fishing_level}", String.valueOf(getLevel(uuid, "fishing")))
                    .replace("{exploring_level}", String.valueOf(getLevel(uuid, "exploring")));
            String line = ChatColor.translateAlternateColorCodes('&', rawLine);

            String entry = colors[i % colors.length].toString() + ChatColor.RESET;
            Team team = board.registerNewTeam("line" + i);
            team.addEntry(entry);
            team.setPrefix(line);
            objective.getScore(entry).setScore(score);
            score--;
        }

        player.setScoreboard(board);
    }

    private void clearScoreboard(Player player) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager != null) {
            player.setScoreboard(manager.getMainScoreboard());
        }
    }

    // ---------- Events: join/quit ----------

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isScoreboardEnabled(player.getUniqueId())) {
            scoreboardShown.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) updateScoreboard(player);
            }, 5L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        scoreboardShown.remove(uuid);
        explorationProgress.remove(uuid);
    }

    // ---------- Events: mijnen ----------

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Block block = event.getBlock();
        if (ORES.contains(block.getType())) {
            addCategoryXp(event.getPlayer(), "mining", miningPerOre, "Mijnen");
            return;
        }

        BlockData blockData = block.getBlockData();
        if (blockData instanceof Ageable) {
            Ageable ageable = (Ageable) blockData;
            if (ageable.getAge() == ageable.getMaximumAge()) {
                addCategoryXp(event.getPlayer(), "farming", farmingPerCrop, "Boeren");
            }
        }
    }

    // ---------- Events: vissen ----------

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.isCancelled()) return;
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;

        addCategoryXp(event.getPlayer(), "fishing", fishingPerCatch, "Vissen");
    }

    // ---------- Events: verkennen ----------

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        if (exploringPerBlocks <= 0) return;

        double dx = event.getTo().getX() - event.getFrom().getX();
        double dz = event.getTo().getZ() - event.getFrom().getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        // Grote sprongen (teleports, /lobby, /spawn, /survival) niet meetellen.
        if (distance <= 0 || distance > 10) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        double progress = explorationProgress.getOrDefault(uuid, 0.0) + distance;
        if (progress >= exploringPerBlocks) {
            int points = (int) (progress / exploringPerBlocks);
            progress -= points * (double) exploringPerBlocks;
            addCategoryXp(player, "exploring", points, "Verkennen");
        }
        explorationProgress.put(uuid, progress);
    }
}
