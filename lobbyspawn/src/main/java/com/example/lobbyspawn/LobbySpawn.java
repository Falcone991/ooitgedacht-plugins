package com.example.lobbyspawn;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class LobbySpawn extends JavaPlugin implements CommandExecutor, Listener {

    // Lobby-locatie (uit config.yml)
    private boolean lobbySet;
    private String lobbyWorld;
    private double lobbyX, lobbyY, lobbyZ;
    private float lobbyYaw, lobbyPitch;

    // Spawn-mobbescherming (uit config.yml)
    private boolean protectionEnabled;
    private int protectionRadius;

    // Blokken breken/plaatsen bij spawn (uit config.yml, vervangt vanilla)
    private boolean buildProtectionEnabled;
    private int buildProtectionRadius;
    private String buildProtectionMessage;
    private boolean buildProtectionSoundEnabled;
    private boolean buildProtectionBlockInteractions;

    // Welkomstbericht / regels (uit config.yml)
    private boolean welcomeEnabled;
    private int welcomeDelayTicks;
    private List<String> welcomeLines;

    // Exacte spawn i.p.v. vanilla-randomisatie (uit config.yml)
    private boolean forceExactSpawn;

    // Hub-gedrag: iedereen start bij het inloggen in de lobby (uit config.yml)
    private boolean hubEnabled;
    private String survivalWorldName;

    // Delay-instellingen (uit config.yml)
    private int warmupSeconds;
    private int cooldownSeconds;

    // /spawn instellingen (uit config.yml)
    private int spawnWarmupSeconds;
    private int spawnCooldownSeconds;

    // Lopende warmups: speler -> geplande teleport-taak (gedeeld door /lobby en /spawn)
    private final Map<UUID, BukkitTask> pendingTeleports = new HashMap<>();
    // Startlocatie bij begin van de warmup, om beweging te detecteren
    private final Map<UUID, Location> warmupStartLocations = new HashMap<>();
    // Naam van de bestemming tijdens een lopende warmup, voor nette berichten
    private final Map<UUID, String> pendingDestinationLabel = new HashMap<>();
    // Laatste succesvolle teleport-tijdstip per speler, voor de cooldown (los per commando)
    private final Map<UUID, Long> lastLobbyTeleport = new HashMap<>();
    private final Map<UUID, Long> lastSpawnTeleport = new HashMap<>();

    // Persistente per-speler data (laatst bekende survival-locatie)
    private File dataFile;
    private FileConfiguration data;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadData();

        getCommand("lobby").setExecutor(this);
        getCommand("spawn").setExecutor(this);
        getCommand("setlobby").setExecutor(this);
        getCommand("spawnprotection").setExecutor(this);
        getCommand("buildprotection").setExecutor(this);
        getCommand("rules").setExecutor(this);
        getCommand("survival").setExecutor(this);

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("LobbySpawn geladen. Lobby ingesteld: " + lobbySet
                + ", mob-spawnbescherming: " + protectionRadius + " blokken (aan: " + protectionEnabled + ")"
                + ", hub-modus: " + hubEnabled
                + ", warmup: " + warmupSeconds + "s, cooldown: " + cooldownSeconds + "s.");
    }

    @Override
    public void onDisable() {
        saveData();
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();

        lobbySet = c.getBoolean("lobby.set", false);
        lobbyWorld = c.getString("lobby.world", "");
        lobbyX = c.getDouble("lobby.x", 0.5);
        lobbyY = c.getDouble("lobby.y", 64.0);
        lobbyZ = c.getDouble("lobby.z", 0.5);
        lobbyYaw = (float) c.getDouble("lobby.yaw", 0.0);
        lobbyPitch = (float) c.getDouble("lobby.pitch", 0.0);
        warmupSeconds = c.getInt("lobby.warmup-seconds", 3);
        cooldownSeconds = c.getInt("lobby.cooldown-seconds", 30);

        spawnWarmupSeconds = c.getInt("spawn-command.warmup-seconds", 3);
        spawnCooldownSeconds = c.getInt("spawn-command.cooldown-seconds", 15);

        protectionEnabled = c.getBoolean("spawn-protection.enabled", true);
        protectionRadius = c.getInt("spawn-protection.radius", 100);

        buildProtectionEnabled = c.getBoolean("build-protection.enabled", true);
        buildProtectionRadius = c.getInt("build-protection.radius", 16);
        buildProtectionMessage = c.getString("build-protection.message",
                "&cDeze plek valt binnen de spawn-bescherming. Je mag hier niet bouwen of afbreken.");
        buildProtectionSoundEnabled = c.getBoolean("build-protection.sound-enabled", true);
        buildProtectionBlockInteractions = c.getBoolean("build-protection.block-interactions", true);

        welcomeEnabled = c.getBoolean("welcome-message.enabled", true);
        welcomeDelayTicks = c.getInt("welcome-message.delay-ticks", 20);
        welcomeLines = c.getStringList("welcome-message.lines");

        forceExactSpawn = c.getBoolean("world-spawn.force-exact-spawn", true);

        hubEnabled = c.getBoolean("hub.enabled", false);
        String configuredSurvivalWorld = c.getString("hub.survival-world", "");
        if (configuredSurvivalWorld == null || configuredSurvivalWorld.isEmpty()) {
            survivalWorldName = Bukkit.getWorlds().get(0).getName();
        } else {
            survivalWorldName = configuredSurvivalWorld;
        }
    }

    private void saveSettings() {
        FileConfiguration c = getConfig();

        c.set("lobby.set", lobbySet);
        c.set("lobby.world", lobbyWorld);
        c.set("lobby.x", lobbyX);
        c.set("lobby.y", lobbyY);
        c.set("lobby.z", lobbyZ);
        c.set("lobby.yaw", (double) lobbyYaw);
        c.set("lobby.pitch", (double) lobbyPitch);
        c.set("lobby.warmup-seconds", warmupSeconds);
        c.set("lobby.cooldown-seconds", cooldownSeconds);

        c.set("spawn-command.warmup-seconds", spawnWarmupSeconds);
        c.set("spawn-command.cooldown-seconds", spawnCooldownSeconds);

        c.set("spawn-protection.enabled", protectionEnabled);
        c.set("spawn-protection.radius", protectionRadius);

        c.set("build-protection.enabled", buildProtectionEnabled);
        c.set("build-protection.radius", buildProtectionRadius);
        c.set("build-protection.message", buildProtectionMessage);
        c.set("build-protection.sound-enabled", buildProtectionSoundEnabled);
        c.set("build-protection.block-interactions", buildProtectionBlockInteractions);

        saveConfig();
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

    private void saveLastLocation(UUID uuid, Location loc) {
        String path = "players." + uuid;
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
        data.set(path + ".yaw", (double) loc.getYaw());
        data.set(path + ".pitch", (double) loc.getPitch());
        saveData();
    }

    private Location loadLastLocation(UUID uuid) {
        String path = "players." + uuid;
        if (!data.contains(path + ".world")) return null;

        World world = Bukkit.getWorld(data.getString(path + ".world", ""));
        if (world == null) return null;

        double x = data.getDouble(path + ".x");
        double y = data.getDouble(path + ".y");
        double z = data.getDouble(path + ".z");
        float yaw = (float) data.getDouble(path + ".yaw");
        float pitch = (float) data.getDouble(path + ".pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "lobby":
                return handleLobby(sender);
            case "spawn":
                return handleSpawn(sender);
            case "setlobby":
                return handleSetLobby(sender);
            case "spawnprotection":
                return handleSpawnProtection(sender, args);
            case "buildprotection":
                return handleBuildProtection(sender, args);
            case "rules":
                return handleRules(sender);
            case "survival":
                return handleSurvival(sender);
            default:
                return false;
        }
    }

    private boolean handleRules(CommandSender sender) {
        if (welcomeLines == null || welcomeLines.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Er is geen regelsbericht ingesteld.");
            return true;
        }
        for (String line : welcomeLines) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
        }
        return true;
    }

    private boolean handleSurvival(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;

        Location dest = loadLastLocation(player.getUniqueId());
        if (dest == null) {
            World world = Bukkit.getWorld(survivalWorldName);
            if (world == null) {
                player.sendMessage(ChatColor.RED + "De survival-wereld kon niet gevonden worden.");
                return true;
            }
            dest = world.getSpawnLocation();
        }

        player.teleport(dest);
        player.sendMessage(ChatColor.GREEN + "Welkom terug in survival!");
        return true;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        boolean firstJoin = !player.hasPlayedBefore();

        if (hubEnabled && lobbySet) {
            // Hub-modus: iedereen komt bij het inloggen eerst in de lobby.
            World lobbyWorldObj = Bukkit.getWorld(lobbyWorld);
            if (lobbyWorldObj != null) {
                Location lobbyLoc = new Location(lobbyWorldObj, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyPitch);
                Bukkit.getScheduler().runTask(this, () -> {
                    if (player.isOnline()) {
                        player.teleport(lobbyLoc);
                    }
                });
            }
        } else if (firstJoin && forceExactSpawn) {
            // Geen hub-modus: corrigeer in ieder geval de willekeurige
            // vanilla-spreiding bij de allereerste keer inloggen.
            World world = player.getWorld();
            Bukkit.getScheduler().runTask(this, () -> {
                if (player.isOnline()) {
                    player.teleport(world.getSpawnLocation());
                }
            });
        }

        if (!welcomeEnabled || welcomeLines == null || welcomeLines.isEmpty()) return;
        if (!firstJoin) return; // alleen bij de allereerste keer inloggen

        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) return;
            for (String line : welcomeLines) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
            }
        }, welcomeDelayTicks);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        BukkitTask task = pendingTeleports.remove(uuid);
        if (task != null) task.cancel();
        warmupStartLocations.remove(uuid);

        // Onthoud waar de speler was, zodat /survival hem daar weer
        // terugbrengt. Niet opslaan als hij toevallig in de lobby-wereld
        // stond, anders raken we de echte laatste positie kwijt.
        if (hubEnabled) {
            World world = player.getWorld();
            boolean inLobby = lobbySet && world.getName().equals(lobbyWorld);
            if (!inLobby) {
                saveLastLocation(uuid, player.getLocation());
            }
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        // Alleen overschrijven als de speler geen eigen bed/anker-spawn
        // heeft; die respawnpunten willen we natuurlijk niet aanpassen.
        if (!forceExactSpawn) return;
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;

        World world = event.getRespawnLocation().getWorld();
        if (world == null) return;

        event.setRespawnLocation(world.getSpawnLocation());
    }

    private boolean handleLobby(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;

        if (!lobbySet) {
            player.sendMessage(ChatColor.RED + "Er is nog geen lobby ingesteld. Vraag een admin om /setlobby te gebruiken.");
            return true;
        }

        World world = Bukkit.getWorld(lobbyWorld);
        if (world == null) {
            player.sendMessage(ChatColor.RED + "De lobby-wereld kon niet gevonden worden.");
            return true;
        }

        // Onthoud, indien hub-modus aan staat, waar je vandaan kwam (mits
        // niet al in de lobby), zodat /survival straks weer terugbrengt.
        if (hubEnabled && !world.getName().equals(player.getWorld().getName())) {
            saveLastLocation(player.getUniqueId(), player.getLocation());
        }

        requestTeleport(player, "de lobby",
                () -> {
                    World w = Bukkit.getWorld(lobbyWorld);
                    return w == null ? null : new Location(w, lobbyX, lobbyY, lobbyZ, lobbyYaw, lobbyPitch);
                },
                warmupSeconds, cooldownSeconds, lastLobbyTeleport);
        return true;
    }

    private boolean handleSpawn(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;

        requestTeleport(player, "spawn",
                () -> {
                    World w = Bukkit.getWorld(survivalWorldName);
                    return w == null ? null : w.getSpawnLocation();
                },
                spawnWarmupSeconds, spawnCooldownSeconds, lastSpawnTeleport);
        return true;
    }

    /**
     * Generieke teleport-met-countdown, gebruikt door zowel /lobby als /spawn.
     * destinationSupplier wordt zowel meteen als (bij een warmup) na afloop
     * opnieuw aangeroepen, zodat een intussen gewijzigde bestemming klopt.
     */
    private void requestTeleport(Player player, String destinationLabel,
                                  java.util.function.Supplier<Location> destinationSupplier,
                                  int warmupSecondsLocal, int cooldownSecondsLocal,
                                  Map<UUID, Long> cooldownMap) {
        UUID uuid = player.getUniqueId();
        boolean bypass = player.hasPermission("lobbyspawn.bypassdelay");

        // Cooldown check (overgeslagen bij bypass-recht)
        if (!bypass && cooldownSecondsLocal > 0) {
            Long last = cooldownMap.get(uuid);
            if (last != null) {
                long secondsSince = (System.currentTimeMillis() - last) / 1000L;
                if (secondsSince < cooldownSecondsLocal) {
                    long remaining = cooldownSecondsLocal - secondsSince;
                    player.sendMessage(ChatColor.RED + "Je moet nog " + remaining
                            + " seconde(n) wachten voor je dit weer kan gebruiken.");
                    return;
                }
            }
        }

        // Al een warmup bezig? Dan niet opnieuw starten.
        if (pendingTeleports.containsKey(uuid)) {
            player.sendMessage(ChatColor.YELLOW + "Je teleport is al bezig, blijf stilstaan...");
            return;
        }

        if (bypass || warmupSecondsLocal <= 0) {
            executeTeleport(player, destinationLabel, destinationSupplier, cooldownMap);
            return;
        }

        // Warmup starten
        warmupStartLocations.put(uuid, player.getLocation());
        pendingDestinationLabel.put(uuid, destinationLabel);
        player.sendMessage(ChatColor.YELLOW + "Je teleporteert over " + warmupSecondsLocal
                + " seconde(n) naar " + destinationLabel + ". Blijf stilstaan en zorg dat je geen schade oploopt!");

        BukkitTask task = Bukkit.getScheduler().runTaskLater(this, () -> {
            pendingTeleports.remove(uuid);
            warmupStartLocations.remove(uuid);
            pendingDestinationLabel.remove(uuid);
            if (player.isOnline()) {
                executeTeleport(player, destinationLabel, destinationSupplier, cooldownMap);
            }
        }, warmupSecondsLocal * 20L);

        pendingTeleports.put(uuid, task);
    }

    private void executeTeleport(Player player, String destinationLabel,
                                  java.util.function.Supplier<Location> destinationSupplier,
                                  Map<UUID, Long> cooldownMap) {
        Location dest = destinationSupplier.get();
        if (dest == null) {
            player.sendMessage(ChatColor.RED + "De bestemming (" + destinationLabel + ") kon niet gevonden worden.");
            return;
        }
        player.teleport(dest);
        player.sendMessage(ChatColor.GREEN + "Je bent naar " + destinationLabel + " geteleporteerd.");
        cooldownMap.put(player.getUniqueId(), System.currentTimeMillis());
    }

    private void cancelWarmup(Player player, String reason) {
        UUID uuid = player.getUniqueId();
        BukkitTask task = pendingTeleports.remove(uuid);
        warmupStartLocations.remove(uuid);
        String label = pendingDestinationLabel.remove(uuid);
        if (task != null) {
            task.cancel();
            player.sendMessage(ChatColor.RED + "Teleport naar " + (label != null ? label : "je bestemming")
                    + " geannuleerd: " + reason);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location start = warmupStartLocations.get(uuid);
        if (start == null) return;

        // Alleen annuleren bij echte verplaatsing, niet bij enkel rondkijken.
        Location to = event.getTo();
        if (to == null) return;
        if (start.getBlockX() != to.getBlockX()
                || start.getBlockY() != to.getBlockY()
                || start.getBlockZ() != to.getBlockZ()) {
            cancelWarmup(event.getPlayer(), "je bewoog.");
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        if (pendingTeleports.containsKey(player.getUniqueId())) {
            cancelWarmup(player, "je liep schade op.");
        }
    }

    private boolean handleSetLobby(CommandSender sender) {
        if (!sender.hasPermission("lobbyspawn.admin")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;
        Location loc = player.getLocation();

        // X/Z centreren op het midden van het blok waar je op staat, en de
        // exacte Y van je huidige positie gebruiken. Zo land je bij /lobby
        // altijd precies op het blok, in plaats van op een willekeurige
        // decimale positie die soms tot "zweven" kan leiden.
        double x = Math.floor(loc.getX()) + 0.5;
        double z = Math.floor(loc.getZ()) + 0.5;
        double y = loc.getY();

        lobbyWorld = loc.getWorld().getName();
        lobbyX = x;
        lobbyY = y;
        lobbyZ = z;
        lobbyYaw = loc.getYaw();
        lobbyPitch = loc.getPitch();
        lobbySet = true;

        saveSettings();

        sender.sendMessage(ChatColor.GREEN + "Lobby ingesteld op je huidige positie ("
                + formatCoord(x) + ", " + formatCoord(y) + ", " + formatCoord(z) + ") in wereld " + lobbyWorld + ".");
        return true;
    }

    private boolean handleSpawnProtection(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lobbyspawn.admin")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /spawnprotection <blokken>");
            return true;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Geef een geldig getal op.");
            return true;
        }
        if (radius < 0) {
            sender.sendMessage(ChatColor.RED + "De straal moet 0 of hoger zijn.");
            return true;
        }

        protectionRadius = radius;
        protectionEnabled = true;
        saveSettings();

        sender.sendMessage(ChatColor.GREEN + "Mob-spawnbescherming ingesteld op " + radius
                + " blokken rond het wereld-spawnpunt.");
        return true;
    }

    private boolean handleBuildProtection(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lobbyspawn.admin")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (args.length != 1) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /buildprotection <blokken>");
            return true;
        }

        int radius;
        try {
            radius = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            sender.sendMessage(ChatColor.RED + "Geef een geldig getal op.");
            return true;
        }
        if (radius < 0) {
            sender.sendMessage(ChatColor.RED + "De straal moet 0 of hoger zijn.");
            return true;
        }

        buildProtectionRadius = radius;
        buildProtectionEnabled = true;
        saveSettings();

        sender.sendMessage(ChatColor.GREEN + "Bouw-bescherming ingesteld op " + radius
                + " blokken rond het wereld-spawnpunt.");
        return true;
    }

    private boolean isInBuildProtection(Location loc) {
        if (!buildProtectionEnabled || buildProtectionRadius <= 0) return false;

        World world = loc.getWorld();
        if (world == null) return false;

        Location spawnPoint = world.getSpawnLocation();
        double dx = loc.getX() - spawnPoint.getX();
        double dz = loc.getZ() - spawnPoint.getZ();

        return dx * dx + dz * dz <= (double) buildProtectionRadius * buildProtectionRadius;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("lobbyspawn.admin")) return;

        if (isInBuildProtection(event.getBlock().getLocation())) {
            event.setCancelled(true);
            notifyBuildProtection(player);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("lobbyspawn.admin")) return;

        if (isInBuildProtection(event.getBlock().getLocation())) {
            event.setCancelled(true);
            notifyBuildProtection(player);
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (!buildProtectionBlockInteractions) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block block = event.getClickedBlock();
        if (block == null || !isInteractableBlock(block.getType())) return;

        Player player = event.getPlayer();
        if (player.hasPermission("lobbyspawn.admin")) return;

        if (isInBuildProtection(block.getLocation())) {
            event.setCancelled(true);
            notifyBuildProtection(player);
        }
    }

    /**
     * Deuren, poortjes, luiken, knoppen, hendels en drukplaten — dingen die
     * je met een simpele rechtermuisklik kan bedienen zonder dat het als
     * "bouwen" telt, maar die je bij spawn ook niet wil laten gebruiken.
     */
    private boolean isInteractableBlock(Material type) {
        String name = type.name();
        return name.endsWith("_DOOR")
                || name.endsWith("_TRAPDOOR")
                || name.endsWith("_GATE")
                || name.endsWith("_BUTTON")
                || name.endsWith("_LEVER") || name.equals("LEVER")
                || name.endsWith("_PRESSURE_PLATE");
    }

    private void notifyBuildProtection(Player player) {
        player.sendActionBar(ChatColor.translateAlternateColorCodes('&', buildProtectionMessage));
        if (buildProtectionSoundEnabled) {
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        }
    }

    @EventHandler
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (!protectionEnabled || protectionRadius <= 0) return;
        if (!(event.getEntity() instanceof Monster)) return; // alleen vijandige mobs blokkeren

        World world = event.getLocation().getWorld();
        if (world == null) return;

        Location spawnPoint = world.getSpawnLocation();

        double dx = event.getLocation().getX() - spawnPoint.getX();
        double dz = event.getLocation().getZ() - spawnPoint.getZ();
        double distanceSquared = dx * dx + dz * dz;

        if (distanceSquared <= (double) protectionRadius * protectionRadius) {
            event.setCancelled(true);
        }
    }

    private String formatCoord(double value) {
        return String.format("%.1f", value);
    }
}
