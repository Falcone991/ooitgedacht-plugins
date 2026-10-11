package com.example.survivaltimeline;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

    // Finale (uit config.yml)
    private boolean finaleEnabled;
    private int finaleStartWeek;
    private int finaleDays;
    private LocalTime shrinkTime;
    private ZoneId zone;
    private int warnMinutes;
    private int graceMinutes;
    private double finaleMinSize;
    private long shrinkSeconds;
    private boolean finaleCloseNetherEnd;
    private int inactiveHours;

    // Status (uit data.yml, blijft bewaard na herstart)
    private long startTime;
    private boolean hardcoreActivated;
    private boolean netherUnlocked;
    private boolean endUnlocked;
    private long lastCountedDay;
    private boolean finaleStarted;
    private double finaleStartSize;   // grootte van de grens bij de start van de finale
    private int finaleShrinksDone;    // hoeveel dagelijkse krimp-stappen al gedaan zijn
    private int warnedFor = -1;       // alleen in het geheugen: voor welke stap al gewaarschuwd is
    private int finaleEliminations;
    private double finaleBorderTarget;
    private long lastShrinkAt;
    private String winner;
    private boolean seasonEnded;
    private boolean testActive;   // testmodus: stand van voor de test staat in test-backup.yml
    private long finaleStartedAt;     // wanneer de finale begon (voor de inactiviteitsgrens)
    private UUID lastEliminated;       // wie als laatste afviel (wint als er niemand meer over is)
    // Alleen tijdens /stimeline test eindstrijd: wie er meedoet (anders tellen alle spelers ooit mee).
    private final Set<UUID> testParticipants = new HashSet<>();

    // Spelers: wanneer laatst in survival gezien, en wie er in hardcore dood is gegaan.
    private final Map<UUID, Long> lastSeen = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<UUID> eliminated = new HashSet<>();
    // Na de winst of een reset: deze (dode) spelers komen weer tot leven, ook als ze nu offline zijn.
    private final Set<UUID> revivePending = new HashSet<>();
    private final Set<UUID> sendingBack = new HashSet<>();
    private final Map<UUID, Location> deathLocations = new HashMap<>();
    private int ticks;
    private ReturnGrace returnGrace;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        loadData();

        Bukkit.getPluginManager().registerEvents(this, this);
        returnGrace = new ReturnGrace(this);
        Bukkit.getPluginManager().registerEvents(returnGrace, this);
        Bukkit.getScheduler().runTaskTimer(this, returnGrace::tick, 20L, 20L);

        // Elke seconde controleren (20 ticks). Vaak genoeg voor "weken" en
        // "in-game dagen", en licht genoeg om de server niet te belasten.
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 20L, 20L);

        getLogger().info("SurvivalTimeline actief. Gestart op: " + new java.util.Date(startTime));
        getLogger().info("Hardcore na " + weeksBeforeHardcore + " volledige week(en), Nether open vanaf week "
                + netherOpenWeek + ", End open vanaf week " + endOpenWeek + ".");
        if (finaleEnabled) {
            getLogger().info("Finale vanaf week " + finaleStartWeek + ": border elke dag om " + shrinkTime + " kleiner, na "
                    + finaleDays + " dagen " + formatNumber(finaleMinSize) + " blokken."
                    + (winner != null ? " Winnaar: " + winner + "." : finaleStarted ? " (bezig)" : ""));
        }
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
        borderStartSize = config.getDouble("border-start-size", 6000.0);
        announce = config.getBoolean("announce", true);

        finaleEnabled = config.getBoolean("finale.enabled", true);
        finaleStartWeek = config.getInt("finale.start-week", 4);
        finaleDays = Math.max(1, config.getInt("finale.days", 7));
        try {
            shrinkTime = LocalTime.parse(config.getString("finale.shrink-time", "20:00").trim());
        } catch (RuntimeException e) {
            getLogger().warning("finale.shrink-time moet de vorm UU:MM hebben (bv. 20:00); 20:00 wordt gebruikt.");
            shrinkTime = LocalTime.of(20, 0);
        }
        try {
            zone = ZoneId.of(config.getString("finale.timezone", "Europe/Amsterdam").trim());
        } catch (RuntimeException e) {
            zone = ZoneId.of("Europe/Amsterdam");
        }
        warnMinutes = Math.max(0, config.getInt("finale.warn-minutes", 10));
        graceMinutes = Math.max(0, config.getInt("finale.return-grace-minutes", 30));
        finaleMinSize = Math.max(1.0, config.getDouble("finale.min-size", 50.0));
        shrinkSeconds = Math.max(1L, config.getLong("finale.shrink-seconds", 1800L));
        finaleCloseNetherEnd = config.getBoolean("finale.close-nether-end", true);
        inactiveHours = Math.max(1, config.getInt("finale.inactive-hours", 24));

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
        readState();

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

    /** Alle status uit 'data' in het geheugen zetten (bij opstarten, en bij het terugzetten na een test). */
    private void readState() {
        lastSeen.clear();
        names.clear();
        eliminated.clear();
        revivePending.clear();
        testParticipants.clear();
        for (String s : data.getStringList("test-participants")) {
            try {
                testParticipants.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // overslaan
            }
        }

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
        finaleStarted = data.getBoolean("finale-started", false);
        finaleStartSize = data.getDouble("finale-start-size", 0);
        finaleShrinksDone = data.getInt("finale-shrinks-done", 0);
        finaleEliminations = data.getInt("finale-eliminations", 0);
        finaleBorderTarget = data.getDouble("finale-border-target", 0);
        lastShrinkAt = data.getLong("finale-last-shrink-at", 0);
        winner = data.getString("winner");
        seasonEnded = data.getBoolean("season-ended", false);

        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) {
            for (String key : players.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    lastSeen.put(uuid, players.getLong(key + ".last-seen", 0));
                    names.put(uuid, players.getString(key + ".name", key));
                    if (players.getBoolean(key + ".eliminated", false)) eliminated.add(uuid);
                    if (players.getBoolean(key + ".revive", false)) revivePending.add(uuid);
                } catch (IllegalArgumentException ignored) {
                    // ongeldige regel overslaan
                }
            }
        }
        testActive = data.getBoolean("test-active", false);
        finaleStartedAt = data.getLong("finale-started-at", 0);
        String last = data.getString("finale-last-eliminated");
        try {
            lastEliminated = last == null ? null : UUID.fromString(last);
        } catch (IllegalArgumentException e) {
            lastEliminated = null;
        }
    }

    private void saveData() {
        try {
            data.set("start-time", startTime);
            data.set("hardcore-activated", hardcoreActivated);
            data.set("nether-unlocked", netherUnlocked);
            data.set("end-unlocked", endUnlocked);
            data.set("last-counted-day", lastCountedDay);
            data.set("finale-started", finaleStarted);
            data.set("finale-start-size", finaleStartSize);
            data.set("finale-shrinks-done", finaleShrinksDone);
            data.set("finale-eliminations", finaleEliminations);
            data.set("finale-border-target", finaleBorderTarget);
            data.set("finale-last-shrink-at", lastShrinkAt);
            data.set("winner", winner);
            data.set("season-ended", seasonEnded);
            data.set("test-active", testActive);
            data.set("finale-started-at", finaleStartedAt);
            List<String> participants = new ArrayList<>();
            for (UUID uuid : testParticipants) participants.add(uuid.toString());
            data.set("test-participants", participants.isEmpty() ? null : participants);
            data.set("finale-last-eliminated", lastEliminated == null ? null : lastEliminated.toString());
            for (Map.Entry<UUID, Long> entry : lastSeen.entrySet()) {
                String path = "players." + entry.getKey();
                data.set(path + ".name", names.get(entry.getKey()));
                data.set(path + ".last-seen", entry.getValue());
                data.set(path + ".eliminated", eliminated.contains(entry.getKey()));
                data.set(path + ".revive", revivePending.contains(entry.getKey()));
            }
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Kon data.yml niet opslaan: " + e.getMessage());
        }
    }

    private void tick() {
        ticks++;
        // Testmodus: iedereen elke 5 seconden eraan herinneren.
        if (testActive && ticks % 5 == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize("&e&l⚠ TESTMODUS &7- beheer test de tijdlijn, dit telt niet"));
            }
        }
        // Seizoen voorbij (er is een winnaar): niets meer te doen, iedereen speelt gewoon verder.
        if (seasonEnded) return;

        long elapsedMillis = System.currentTimeMillis() - startTime;
        long elapsedWeeks = TimeUnit.MILLISECONDS.toDays(elapsedMillis) / 7;

        // "Week N" = het begin van de N-de week. Week 1 loopt van dag 0 t/m 6,
        // week 2 begint op dag 7, week 3 op dag 14, enzovoort.
        long currentWeek = elapsedWeeks + 1;

        checkHardcore(elapsedWeeks);
        checkNether(currentWeek);
        checkEnd(currentWeek);
        checkFinaleStart(currentWeek);
        checkWorldBorder();
        trackPlayers();

        if (finaleStarted && winner == null) {
            checkShrink();
            if (finaleCloseNetherEnd) sendBackFromNetherEnd();
        }

        // Elke minuut: opslaan (laatst gezien) en kijken of er al een winnaar is.
        if (ticks % 60 == 0) {
            saveData();
            checkInactive();
            checkWinner();
        }
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
            lastCountedDay = currentDay;
            saveData();

            // Tijdens de finale groeit de grens niet meer, hij krimpt juist.
            if (finaleStarted) return;

            WorldBorder border = world.getWorldBorder();
            double increase = borderIncrementPerDay * daysPassed;
            double newSize = border.getSize() + increase;

            // Rustig laten "groeien" over 60 seconden i.p.v. abrupt springen.
            border.setSize(newSize, 60L);

            if (announce) {
                Bukkit.broadcastMessage(ChatColor.AQUA + "De wereldgrens groeit met "
                        + formatNumber(increase) + " blokken! Nieuwe grootte: " + formatNumber(newSize));
            }
        }
    }

    // ---------- Finale ----------

    private void checkFinaleStart(long currentWeek) {
        if (!finaleEnabled || finaleStarted || currentWeek < finaleStartWeek) return;

        finaleStarted = true;
        finaleStartedAt = System.currentTimeMillis();
        finaleShrinksDone = 0;
        World main = Bukkit.getWorld(mainWorldName);
        finaleStartSize = main != null ? main.getWorldBorder().getSize() : 0;
        saveData();
        getLogger().info("Finale gestart (week " + currentWeek + "), grens " + formatNumber(finaleStartSize) + " blokken.");

        showAll("&4&lDE FINALE BEGINT", "&cElke dag om " + shrinkTime + " krimpt de wereldgrens. Wie blijft er over?");
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 0.8f);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "De finale is begonnen!");
        Bukkit.broadcastMessage(ChatColor.RED + "Elke dag om " + shrinkTime + " wordt de wereldgrens kleiner. Na "
                + finaleDays + " dagen is hij nog maar " + formatNumber(finaleMinSize) + " blokken. De laatste overlevende wint!");
        Bukkit.broadcastMessage(ChatColor.GRAY + "Eerste keer: " + formatWhen(shrinkMoment(1)) + ", van "
                + formatNumber(finaleStartSize) + " naar " + formatNumber(targetAfter(1)) + " blokken.");
        if (finaleCloseNetherEnd) {
            Bukkit.broadcastMessage(ChatColor.RED + "De Nether en het End zijn vanaf nu dicht.");
        }
    }

    // ---------- Krimpen: elke dag om (standaard) 20:00 ----------

    /** Moment van de n-de krimp (1 = de eerste keer het krimp-tijdstip na de start van de finale). */
    private ZonedDateTime shrinkMoment(int n) {
        ZonedDateTime start = Instant.ofEpochMilli(finaleStartedAt).atZone(zone);
        ZonedDateTime first = start.toLocalDate().atTime(shrinkTime).atZone(zone);
        if (!first.isAfter(start)) first = first.plusDays(1);
        return first.plusDays(n - 1L);
    }

    /** Hoeveel keer had de grens op dit moment al moeten krimpen (hooguit 'days')? */
    private int shrinksDue(long at) {
        ZonedDateTime when = Instant.ofEpochMilli(at).atZone(zone);
        int n = 0;
        while (n < finaleDays && !shrinkMoment(n + 1).isAfter(when)) n++;
        return n;
    }

    /** Grootte na de n-de krimp: in gelijke stappen van de startgrootte naar het minimum. */
    private double targetAfter(int n) {
        if (n >= finaleDays) return finaleMinSize;
        double start = Math.max(finaleStartSize, finaleMinSize);
        return Math.round(start - (start - finaleMinSize) * n / finaleDays);
    }

    private String formatWhen(ZonedDateTime when) {
        ZonedDateTime now = ZonedDateTime.now(zone);
        String day = when.toLocalDate().equals(now.toLocalDate()) ? "vandaag"
                : when.toLocalDate().equals(now.toLocalDate().plusDays(1)) ? "morgen"
                : when.format(DateTimeFormatter.ofPattern("EEEE d MMMM", new java.util.Locale("nl", "NL")));
        return day + " om " + when.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private void checkShrink() {
        World world = Bukkit.getWorld(mainWorldName);
        if (world == null) return;
        if (finaleStartSize <= 0) finaleStartSize = world.getWorldBorder().getSize(); // oude data
        long now = System.currentTimeMillis();

        // Waarschuwing vooraf.
        if (warnMinutes > 0 && finaleShrinksDone < finaleDays) {
            int soon = shrinksDue(now + TimeUnit.MINUTES.toMillis(warnMinutes));
            if (soon > finaleShrinksDone && shrinksDue(now) == finaleShrinksDone && warnedFor != soon) {
                warnedFor = soon;
                Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Over " + warnMinutes + " minuten "
                        + ChatColor.GOLD + "krimpt de wereldgrens naar " + formatNumber(targetAfter(soon)) + " blokken!");
                for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.8f);
            }
        }

        // Gemist omdat de server sliep? Dan nu in één keer bijwerken.
        int due = shrinksDue(now);
        if (due > finaleShrinksDone) shrinkTo(world, due);
    }

    /** Grens naar de grootte van stap 'step' laten krimpen (in 'shrink-seconds'). @return false als er niets te krimpen viel. */
    private boolean shrinkTo(World world, int step) {
        WorldBorder border = world.getWorldBorder();
        double current = border.getSize();
        double target = targetAfter(step);
        finaleShrinksDone = step;
        if (target >= current) {
            saveData();
            return false;
        }
        border.setSize(target, shrinkSeconds);
        finaleBorderTarget = target;
        lastShrinkAt = System.currentTimeMillis();
        saveData();

        Bukkit.broadcastMessage(ChatColor.RED + "" + ChatColor.BOLD + "De wereldgrens krimpt! " + ChatColor.GRAY
                + "Van " + formatNumber(current) + " naar " + formatNumber(target) + " blokken, in "
                + (shrinkSeconds / 60) + " minuten (dag " + step + " van " + finaleDays + ").");
        Bukkit.broadcastMessage(ChatColor.GRAY + (step >= finaleDays
                ? "Dit was de laatste keer: kleiner wordt hij niet."
                : "Volgende keer: " + formatWhen(shrinkMoment(step + 1)) + ", naar " + formatNumber(targetAfter(step + 1)) + " blokken."));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1.0f, 0.6f);
        return true;
    }

    /** Tijdens de finale mag niemand (behalve toeschouwers/creative) in de Nether of het End zijn. */
    private void sendBackFromNetherEnd() {
        World main = Bukkit.getWorld(mainWorldName);
        if (main == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            World.Environment env = p.getWorld().getEnvironment();
            if (env == World.Environment.NORMAL) continue;
            if (p.getGameMode() == GameMode.SPECTATOR || p.getGameMode() == GameMode.CREATIVE) continue;
            if (!sendingBack.add(p.getUniqueId())) continue;

            Location target = p.getRespawnLocation();
            if (target == null || target.getWorld() != main || !main.getWorldBorder().isInside(target)) {
                target = main.getSpawnLocation();
                if (!main.getWorldBorder().isInside(target)) {
                    Location center = main.getWorldBorder().getCenter();
                    target = main.getHighestBlockAt(center).getLocation().add(0.5, 1, 0.5);
                }
            }
            p.teleportAsync(target).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
                sendingBack.remove(p.getUniqueId());
                p.sendMessage(ChatColor.RED + "Tijdens de finale zijn de Nether en het End dicht. Je bent teruggebracht naar de overworld.");
            }));
        }
    }

    /** Onthoudt wie er in survival speelt, en wanneer voor het laatst. */
    private void trackPlayers() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() == GameMode.SPECTATOR || !isSurvivalWorld(p.getWorld())) continue;
            lastSeen.put(p.getUniqueId(), now);
            names.put(p.getUniqueId(), p.getName());
        }
    }

    /** De survival-wereld zelf, of de Nether/het End (de lobby is een aparte normale wereld). */
    private boolean isSurvivalWorld(World world) {
        return world.getName().equals(mainWorldName) || world.getEnvironment() != World.Environment.NORMAL;
    }

    /** Spelers die nog meedoen: niet dood in hardcore, en de laatste dagen nog in survival geweest. */
    private List<UUID> alivePlayers() {
        List<UUID> alive = new ArrayList<>();
        for (UUID uuid : testParticipants.isEmpty() ? lastSeen.keySet() : testParticipants) {
            if (!eliminated.contains(uuid)) alive.add(uuid);
        }
        return alive;
    }

    /**
     * Tijdens de finale: wie 'inactive-hours' niet online is geweest, ligt eruit.
     * Er wordt pas geteld vanaf de start van de finale, zodat niemand er meteen uit ligt.
     */
    private void checkInactive() {
        if (!finaleStarted || winner != null) return;
        long now = System.currentTimeMillis();
        long limit = TimeUnit.HOURS.toMillis(inactiveHours);
        boolean changed = false;
        for (Map.Entry<UUID, Long> entry : lastSeen.entrySet()) {
            UUID uuid = entry.getKey();
            if (eliminated.contains(uuid) || Bukkit.getPlayer(uuid) != null) continue;
            if (now - Math.max(entry.getValue(), finaleStartedAt) < limit) continue;
            eliminate(uuid, false);
            changed = true;
            int left = alivePlayers().size();
            Bukkit.broadcastMessage(ChatColor.GRAY + names.getOrDefault(uuid, "?") + " was meer dan " + inactiveHours
                    + " uur niet online en is uitgeschakeld." + (left > 1 ? " Nog " + left + " speler(s) over." : ""));
        }
        if (changed) {
            saveData();
            checkWinner();
        }
    }

    /** @param died true bij echt doodgaan (telt voor "wie als laatste afviel wint"), false bij te lang offline. */
    private void eliminate(UUID uuid, boolean died) {
        eliminated.add(uuid);
        if (finaleStarted && winner == null) {
            finaleEliminations++;
            if (died) lastEliminated = uuid;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (!hardcoreActivated || seasonEnded) return;
        Player player = event.getEntity();
        deathLocations.put(player.getUniqueId(), player.getLocation());
        names.put(player.getUniqueId(), player.getName());
        lastSeen.putIfAbsent(player.getUniqueId(), System.currentTimeMillis());
        eliminate(player.getUniqueId(), true);
        if (finaleStarted && winner == null) {
            int left = alivePlayers().size();
            if (left > 1) {
                Bukkit.broadcastMessage(ChatColor.GRAY + player.getName() + " is uitgeschakeld. Nog "
                        + left + " speler(s) over.");
            }
        }
        saveData();
        giveHead(player);
        Bukkit.getScheduler().runTask(this, this::checkWinner);
    }

    /** Finale: wie een speler doodt, krijgt diens hoofd (vol? dan valt het op de grond). */
    private void giveHead(Player victim) {
        Player killer = victim.getKiller();
        if (!finaleStarted || winner != null || killer == null || killer.equals(victim)) return;

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(victim);
            LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
            meta.displayName(legacy.deserialize("&6Hoofd van " + victim.getName()).decoration(TextDecoration.ITALIC, false));
            meta.lore(java.util.List.of(
                    legacy.deserialize("&7Uitgeschakeld door " + killer.getName()).decoration(TextDecoration.ITALIC, false),
                    legacy.deserialize("&7in de finale van OoitGedacht SMP").decoration(TextDecoration.ITALIC, false)));
            head.setItemMeta(meta);
        }
        for (ItemStack left : killer.getInventory().addItem(head).values()) {
            killer.getWorld().dropItemNaturally(killer.getLocation(), left);
        }
        killer.sendMessage(ChatColor.GOLD + "Je hebt het hoofd van " + victim.getName() + " gekregen.");
        killer.playSound(killer.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 0.8f);
    }

    /**
     * Hardcore: wie dood is, respawnt als toeschouwer op de plek waar die stierf.
     * Minecraft doet dit zelf niet betrouwbaar als de wereld pas tijdens het spelen
     * hardcore is geworden (dan krijg je een gewone "Respawn"-knop).
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!hardcoreActivated || seasonEnded || !eliminated.contains(player.getUniqueId())) return;
        Location death = deathLocations.remove(player.getUniqueId());
        if (death != null && death.getWorld() != null) {
            if (death.getY() < death.getWorld().getMinHeight()) death.setY(death.getWorld().getMinHeight() + 1);
            event.setRespawnLocation(death);
        }
        Bukkit.getScheduler().runTask(this, () -> {
            if (!player.isOnline() || !eliminated.contains(player.getUniqueId()) || seasonEnded) return;
            player.setGameMode(GameMode.SPECTATOR);
            player.sendMessage(ChatColor.RED + "Je bent dood en uitgeschakeld (hardcore). Je kunt nog wel meekijken.");
        });
    }

    /** Eén speler over (en er is in de finale al iemand afgevallen)? Dan is er een winnaar. */
    private void checkWinner() {
        if (!finaleStarted || winner != null || finaleEliminations == 0) return;
        List<UUID> alive = alivePlayers();
        if (alive.size() == 1) {
            declareWinner(alive.get(0), names.getOrDefault(alive.get(0), "?"));
        } else if (alive.isEmpty() && lastEliminated != null) {
            // Niemand meer over (bv. de laatsten gingen tegelijk dood): wie als laatste afviel wint.
            declareWinner(lastEliminated, names.getOrDefault(lastEliminated, "?"));
        }
    }

    /** Winnaar bekendmaken en het seizoen afsluiten. */
    private void declareWinner(UUID uuid, String name) {
        winner = name;
        saveData();
        getLogger().info("Winnaar van de finale: " + winner);

        showAll("&6&l★ " + winner + " WINT! ★", "&eDe laatste overlevende van OoitGedacht SMP");
        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "★ " + winner
                + " is de laatste overlevende en wint OoitGedacht SMP! ★");
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        }
        Player winnerPlayer = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (winnerPlayer != null) celebrate(winnerPlayer);

        // Na het feestje (8 seconden): seizoen voorbij.
        Bukkit.getScheduler().runTaskLater(this, this::endSeason, 160L);
    }

    /**
     * Seizoen voorbij: wereldgrens weg, hardcore uit, Nether/End weer open, en
     * iedereen die dood was komt terug (wie offline is: bij de volgende keer inloggen).
     */
    private void endSeason() {
        if (winner == null || seasonEnded) return; // bv. testmodus intussen gestopt
        seasonEnded = true;
        if (returnGrace != null) returnGrace.clear();
        for (World w : Bukkit.getWorlds()) w.setHardcore(false);
        World main = Bukkit.getWorld(mainWorldName);
        if (main != null) main.getWorldBorder().reset();
        revivePending.addAll(eliminated);
        eliminated.clear();
        saveData();
        getLogger().info("Seizoen afgelopen: wereldgrens weg, hardcore uit.");

        Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Het seizoen is voorbij! " + ChatColor.GREEN
                + "De wereldgrens is weg, respawnen staat weer aan en de Nether en het End zijn open. Speel lekker verder!");
        for (Player p : Bukkit.getOnlinePlayers()) revive(p);
    }

    /** Dode speler (toeschouwer) weer in survival zetten, als die op de lijst staat. */
    private void revive(Player player) {
        if (!revivePending.remove(player.getUniqueId())) return;
        saveData();
        if (player.getGameMode() != GameMode.SPECTATOR) return;
        player.setGameMode(GameMode.SURVIVAL);

        // In survival (of Nether/End): naar je bed, anders naar de spawn. In de lobby blijf je gewoon staan.
        if (isSurvivalWorld(player.getWorld())) {
            World main = Bukkit.getWorld(mainWorldName);
            Location target = player.getRespawnLocation();
            if (target == null && main != null) target = main.getSpawnLocation();
            if (target != null) player.teleportAsync(target);
        }
        player.sendMessage(ChatColor.GREEN + "Je bent weer tot leven gewekt! Je kunt gewoon verder spelen.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Een tick later, zodat andere plugins (bv. de lobby) eerst klaar zijn.
        if (revivePending.contains(player.getUniqueId())) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) revive(player);
            }, 5L);
        }
        // Tijdens de finale uitgeschakeld (bv. te lang offline)? Dan meekijken als toeschouwer.
        if (eliminated.contains(player.getUniqueId()) && finaleStarted && !seasonEnded) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline() || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) return;
                player.setGameMode(GameMode.SPECTATOR);
                player.sendMessage(ChatColor.RED + "Je was tijdens de finale meer dan " + inactiveHours
                        + " uur niet online en bent uitgeschakeld. Je kunt nog wel meekijken.");
            }, 6L);
        }
    }

    /** Vuurwerk hoog boven de winnaar (zo hoog dat het geen schade doet). */
    private void celebrate(Player player) {
        Color[] colors = {Color.YELLOW, Color.ORANGE, Color.AQUA, Color.LIME, Color.FUCHSIA};
        for (int i = 0; i < 8; i++) {
            int n = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!player.isOnline()) return;
                Location loc = player.getLocation().add((n % 3) - 1, 12, (n % 2) * 2 - 1);
                player.getWorld().spawn(loc, Firework.class, fw -> {
                    FireworkMeta meta = fw.getFireworkMeta();
                    meta.addEffect(FireworkEffect.builder().withColor(colors[n % colors.length])
                            .withFade(Color.WHITE).with(FireworkEffect.Type.BALL_LARGE).withFlicker().build());
                    meta.setPower(0);
                    fw.setFireworkMeta(meta);
                });
            }, i * 10L);
        }
    }

    /** Vuurwerk van de winnaar-show doet nooit schade. */
    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework && winner != null) event.setCancelled(true);
    }

    private static void showAll(String title, String subtitle) {
        LegacyComponentSerializer legacy = LegacyComponentSerializer.legacyAmpersand();
        Title t = Title.title(legacy.deserialize(title), legacy.deserialize(subtitle),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(5), Duration.ofSeconds(2)));
        for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(t);
    }

    // ---------- Portalen ----------

    @EventHandler
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null) return;

        World.Environment destination = event.getTo().getWorld().getEnvironment();
        if (destination == World.Environment.NORMAL) return;

        if (finaleStarted && !seasonEnded && finaleCloseNetherEnd) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(ChatColor.RED + "Tijdens de finale zijn de Nether en het End dicht.");
        } else if (destination == World.Environment.NETHER && !netherUnlocked) {
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
        if (args.length > 0) {
            if (!sender.hasPermission("survivaltimeline.admin")) {
                sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
                return true;
            }
            adminCommand(sender, args);
            return true;
        }

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
        sender.sendMessage(ChatColor.GRAY + "Hardcore: " + (seasonEnded
                ? ChatColor.GREEN + "uit (seizoen voorbij)"
                : hardcoreActivated
                ? ChatColor.RED + "actief"
                : ChatColor.GREEN + "nog niet (na " + weeksBeforeHardcore + " volledige weken)"));
        boolean closedForFinale = finaleStarted && !seasonEnded && finaleCloseNetherEnd;
        sender.sendMessage(ChatColor.GRAY + "Nether: " + (closedForFinale ? ChatColor.RED + "dicht (finale)"
                : netherUnlocked ? ChatColor.GREEN + "open"
                : ChatColor.RED + "gesloten (open vanaf week " + netherOpenWeek + ")"));
        sender.sendMessage(ChatColor.GRAY + "End: " + (closedForFinale ? ChatColor.RED + "dicht (finale)"
                : endUnlocked ? ChatColor.GREEN + "open"
                : ChatColor.RED + "gesloten (open vanaf week " + endOpenWeek + ")"));
        if (world != null) {
            sender.sendMessage(ChatColor.GRAY + "Wereldgrens (" + world.getName() + "): "
                    + ChatColor.WHITE + formatNumber(borderSize) + " blokken");
        }
        if (finaleEnabled) {
            if (winner != null) {
                sender.sendMessage(ChatColor.GRAY + "Finale: " + ChatColor.GOLD + "" + ChatColor.BOLD + "gewonnen door " + winner
                        + (seasonEnded ? ChatColor.GRAY + " - seizoen afgelopen, iedereen speelt vrij verder" : ""));
            } else if (finaleStarted) {
                sender.sendMessage(ChatColor.GRAY + "Finale: " + ChatColor.RED + "bezig" + ChatColor.GRAY + " - dag "
                        + finaleShrinksDone + " van " + finaleDays + ", nog " + alivePlayers().size() + " speler(s) in leven");
                sender.sendMessage(ChatColor.GRAY + "Volgende krimp: " + ChatColor.WHITE + (finaleShrinksDone >= finaleDays
                        ? "geen meer (minimum bereikt)"
                        : formatWhen(shrinkMoment(finaleShrinksDone + 1)) + ", naar " + formatNumber(targetAfter(finaleShrinksDone + 1)) + " blokken"));
            } else {
                sender.sendMessage(ChatColor.GRAY + "Finale: " + ChatColor.WHITE + "vanaf week " + finaleStartWeek);
            }
        }
        return true;
    }

    // ---------- Test-/beheercommando's ----------

    private void adminCommand(CommandSender sender, String[] args) {
        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "week": {
                int week;
                try {
                    week = args.length == 2 ? Integer.parseInt(args[1]) : -1;
                } catch (NumberFormatException e) {
                    week = -1;
                }
                if (week < 1) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /stimeline week <nummer>  (bv. 2 = Nether open, 4 = hardcore + finale)");
                    return;
                }
                if (seasonEnded) {
                    sender.sendMessage(ChatColor.RED + "Het seizoen is al afgelopen. Begin opnieuw met /stimeline reset bevestig.");
                    return;
                }
                // Startmoment zo verschuiven dat het nu net het begin van die week is.
                startTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7L * (week - 1)) - 60_000L;
                saveData();
                tick();
                sender.sendMessage(ChatColor.GREEN + "Tijdlijn staat nu op week " + week + ".");
                if (hardcoreActivated && week <= weeksBeforeHardcore || netherUnlocked && week < netherOpenWeek
                        || endUnlocked && week < endOpenWeek || finaleStarted && week < finaleStartWeek) {
                    sender.sendMessage(ChatColor.GRAY + "Let op: fases die al begonnen waren gaan niet vanzelf terug."
                            + " Wil je echt opnieuw beginnen: /stimeline reset bevestig");
                }
                return;
            }
            case "krimp": {
                World world = Bukkit.getWorld(mainWorldName);
                if (!finaleStarted || seasonEnded || world == null) {
                    sender.sendMessage(ChatColor.RED + "De finale is niet bezig. Start hem met /stimeline week " + finaleStartWeek + ".");
                    return;
                }
                if (finaleShrinksDone >= finaleDays || !shrinkTo(world, finaleShrinksDone + 1)) {
                    sender.sendMessage(ChatColor.YELLOW + "De grens is al op het minimum (" + formatNumber(finaleMinSize) + ").");
                }
                return;
            }
            case "winnaar": {
                if (args.length != 2) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /stimeline winnaar <speler>  (doet alsof die speler de finale wint)");
                    return;
                }
                if (winner != null) {
                    sender.sendMessage(ChatColor.RED + "Er is al een winnaar (" + winner + "). Begin opnieuw met /stimeline reset bevestig.");
                    return;
                }
                Player online = Bukkit.getPlayerExact(args[1]);
                declareWinner(online != null ? online.getUniqueId() : null, online != null ? online.getName() : args[1]);
                return;
            }
            case "reset": {
                if (args.length != 2 || !args[1].equalsIgnoreCase("bevestig")) {
                    sender.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Let op: " + ChatColor.RED
                            + "dit zet ALLES terug naar week 1: hardcore uit, Nether/End dicht, finale en winnaar weg,"
                            + " dode spelers weer levend en de wereldgrens terug naar " + formatNumber(borderStartSize) + " blokken.");
                    sender.sendMessage(ChatColor.YELLOW + "Zeker weten? Typ: /stimeline reset bevestig");
                    return;
                }
                resetTimeline();
                sender.sendMessage(ChatColor.GREEN + "Tijdlijn gereset: het is nu week 1.");
                return;
            }
            case "test": {
                String what = args.length == 2 ? args[1].toLowerCase(java.util.Locale.ROOT) : "";
                if (what.equals("start")) testStart(sender);
                else if (what.equals("stop")) testStop(sender);
                else if (what.equals("eindstrijd")) testFinalBattle(sender);
                else sender.sendMessage(ChatColor.YELLOW + "Gebruik: /stimeline test start | eindstrijd | stop");
                return;
            }
            default:
                sender.sendMessage(ChatColor.GOLD + "=== SurvivalTimeline (beheer) ===");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline test start" + ChatColor.GRAY + " - testmodus: huidige stand bewaren");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline test eindstrijd" + ChatColor.GRAY + " - eindstrijd testen: online spelers in de arena van " + formatNumber(finaleMinSize) + " blokken");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline test stop" + ChatColor.GRAY + " - alles terug naar hoe het was vóór de test");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline" + ChatColor.GRAY + " - status");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline week <nummer>" + ChatColor.GRAY + " - spring naar een week (2 = Nether, 3 = End, 4 = hardcore + finale)");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline krimp" + ChatColor.GRAY + " - grens nu meteen één keer laten krimpen (tijdens de finale)");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline winnaar <speler>" + ChatColor.GRAY + " - doen alsof iemand wint (test het einde)");
                sender.sendMessage(ChatColor.YELLOW + "/stimeline reset bevestig" + ChatColor.GRAY + " - alles terug naar week 1");
        }
    }

    // ---------- Testmodus ----------

    private File testBackupFile() {
        return new File(getDataFolder(), "test-backup.yml");
    }

    /** Huidige stand (tijdlijn, spelers, grootte van de grens) bewaren in test-backup.yml. */
    private void testStart(CommandSender sender) {
        if (testActive) {
            sender.sendMessage(ChatColor.RED + "De testmodus staat al aan. Klaar? /stimeline test stop");
            return;
        }
        World main = Bukkit.getWorld(mainWorldName);
        saveData();
        YamlConfiguration backup = YamlConfiguration.loadConfiguration(dataFile);
        if (main != null) {
            backup.set("test-border-size", effectiveTestBorderSize(main));
            backup.set("test-border-center-x", main.getWorldBorder().getCenter().getX());
            backup.set("test-border-center-z", main.getWorldBorder().getCenter().getZ());
        }
        try {
            backup.save(testBackupFile());
        } catch (IOException e) {
            sender.sendMessage(ChatColor.RED + "Kon de stand niet bewaren: " + e.getMessage());
            return;
        }
        testActive = true;
        saveData();
        getLogger().info("Testmodus gestart door " + sender.getName() + ".");
        Bukkit.broadcastMessage(ChatColor.YELLOW + "" + ChatColor.BOLD + "⚠ Testmodus: " + ChatColor.YELLOW
                + "beheer test de tijdlijn. Hardcore, de finale of een krimpende grens tellen nu niet echt;"
                + " na de test wordt alles teruggezet.");
        sender.sendMessage(ChatColor.GREEN + "Stand bewaard. Test nu met /stimeline week, krimp of winnaar."
                + " Klaar? /stimeline test stop");
    }

    /** Grootte van de grens, of waar hij naartoe krimpt als dat nog bezig is. */
    private double effectiveTestBorderSize(World world) {
        WorldBorder border = world.getWorldBorder();
        boolean stillShrinking = finaleStarted && System.currentTimeMillis() - lastShrinkAt < shrinkSeconds * 1000L;
        return stillShrinking ? Math.min(border.getSize(), finaleBorderTarget) : border.getSize();
    }

    /** Alles terugzetten naar de bewaarde stand van vóór de test. */
    private void testStop(CommandSender sender) {
        File file = testBackupFile();
        if (!testActive || !file.isFile()) {
            sender.sendMessage(ChatColor.RED + "De testmodus staat niet aan. Begin met /stimeline test start");
            return;
        }
        Set<UUID> diedInTest = new HashSet<>(eliminated);
        Set<UUID> pendingInTest = new HashSet<>(revivePending);

        data = YamlConfiguration.loadConfiguration(file);
        readState();
        testActive = false;
        diedInTest.removeAll(eliminated);
        pendingInTest.removeAll(eliminated);
        revivePending.addAll(diedInTest);      // tijdens de test doodgegaan: weer tot leven
        revivePending.addAll(pendingInTest);   // stonden nog op de lijst om terug te komen

        World main = Bukkit.getWorld(mainWorldName);
        if (main != null) {
            WorldBorder border = main.getWorldBorder();
            border.setCenter(data.getDouble("test-border-center-x", border.getCenter().getX()),
                    data.getDouble("test-border-center-z", border.getCenter().getZ()));
            border.setSize(data.getDouble("test-border-size", border.getSize()));
            // Zonder dit zou de grens alle in-game dagen van de test in één keer bijgroeien.
            lastCountedDay = main.getFullTime() / 24000L;
        }
        finaleBorderTarget = 0;
        lastShrinkAt = 0;
        for (World w : Bukkit.getWorlds()) w.setHardcore(hardcoreActivated && !seasonEnded);
        data.set("test-border-size", null);
        data.set("test-border-center-x", null);
        data.set("test-border-center-z", null);
        saveData();
        if (!file.delete()) getLogger().warning("Kon test-backup.yml niet weggooien.");
        if (returnGrace != null) returnGrace.clear(); // hulp-tijden uit de test tellen niet

        // Spelers die vóór de test al uitgeschakeld waren (en door de test weer leefden) weer toeschouwer.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (eliminated.contains(p.getUniqueId()) && hardcoreActivated && !seasonEnded
                    && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE) {
                p.setGameMode(GameMode.SPECTATOR);
            }
            revive(p);
        }
        getLogger().info("Testmodus gestopt; stand teruggezet.");
        Bukkit.broadcastMessage(ChatColor.GREEN + "" + ChatColor.BOLD + "Test voorbij: " + ChatColor.GREEN
                + "alles staat weer zoals het was.");
        long week = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - startTime) / 7 + 1;
        sender.sendMessage(ChatColor.GREEN + "Teruggezet: het is weer week " + week + ".");
    }

    /**
     * Eindstrijd testen: finale aan, grens meteen op het minimum, en de spelers die nu
     * online zijn (en niet toeschouwer/creative) in een kring in de arena. Alleen zij
     * tellen mee, dus de laatste die overblijft wint en je ziet het echte winnaarsscherm.
     */
    private void testFinalBattle(CommandSender sender) {
        if (winner != null || seasonEnded) {
            sender.sendMessage(ChatColor.RED + "Er is al een winnaar. Zet eerst alles terug met /stimeline test stop.");
            return;
        }
        World main = Bukkit.getWorld(mainWorldName);
        if (main == null) {
            sender.sendMessage(ChatColor.RED + "De survival-wereld is niet geladen.");
            return;
        }
        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            GameMode mode = p.getGameMode();
            if ((mode == GameMode.SURVIVAL || mode == GameMode.ADVENTURE) && !eliminated.contains(p.getUniqueId())) players.add(p);
        }
        if (players.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Er is niemand online in survival om mee te doen (creative en toeschouwers doen niet mee).");
            return;
        }

        if (!testActive) testStart(sender);
        if (!testActive) return;

        // Finale starten (en daarmee hardcore), als die nog niet bezig was.
        if (!finaleStarted) {
            startTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7L * (finaleStartWeek - 1)) - 60_000L;
            tick();
        }

        // Alleen deze spelers doen mee; nog niemand afgevallen.
        testParticipants.clear();
        long now = System.currentTimeMillis();
        for (Player p : players) {
            testParticipants.add(p.getUniqueId());
            lastSeen.put(p.getUniqueId(), now);
            names.put(p.getUniqueId(), p.getName());
        }
        finaleEliminations = 0;
        lastEliminated = null;

        // Grens meteen op de eindgrootte; alle dagelijkse stappen tellen als gedaan.
        WorldBorder border = main.getWorldBorder();
        border.setSize(finaleMinSize);
        finaleBorderTarget = finaleMinSize;
        lastShrinkAt = now;
        finaleShrinksDone = finaleDays;
        saveData();

        // Spelers in een kring rond het midden, met het gezicht naar het midden.
        Location center = border.getCenter();
        double radius = Math.max(2, finaleMinSize / 2.0 - 6);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            double angle = 2 * Math.PI * i / players.size();
            double x = center.getX() + radius * Math.cos(angle);
            double z = center.getZ() + radius * Math.sin(angle);
            int y = main.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
            float yaw = (float) Math.toDegrees(Math.atan2(-(center.getX() - x), center.getZ() - z));
            Location spot = new Location(main, Math.floor(x) + 0.5, y, Math.floor(z) + 0.5, yaw, 0);
            p.teleportAsync(spot).thenRun(() -> Bukkit.getScheduler().runTask(this, () -> {
                p.setHealth(p.getMaxHealth()); // bestaat in zowel 1.21 als 26.x
                p.setFoodLevel(20);
                p.setSaturation(10f);
                p.setFallDistance(0);
            }));
            list.add(p.getName());
        }

        String vs = String.join(" vs ", list);
        getLogger().info("Eindstrijd-test gestart: " + vs);
        showAll("&4&lEINDSTRIJD &7(test)", "&c" + vs);
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.8f, 1.0f);
        Bukkit.broadcastMessage(ChatColor.DARK_RED + "" + ChatColor.BOLD + "Eindstrijd (test): " + ChatColor.RED + vs
                + ChatColor.GRAY + " - arena van " + formatNumber(finaleMinSize) + " blokken. De laatste overlevende wint!");
        if (players.size() == 1) {
            sender.sendMessage(ChatColor.GRAY + "Je bent alleen; er komt dus geen winnaar uit een gevecht."
                    + " Bekijk het winnaarsscherm met /stimeline winnaar " + players.get(0).getName());
        }
        sender.sendMessage(ChatColor.GREEN + "Klaar met testen? /stimeline test stop zet alles terug (ook wie er dood ging).");
    }

    /** Alles terug naar het begin (week 1), bv. na het testen of voor een nieuw seizoen. */
    private void resetTimeline() {
        startTime = System.currentTimeMillis();
        hardcoreActivated = false;
        netherUnlocked = false;
        endUnlocked = false;
        finaleStarted = false;
        seasonEnded = false;
        winner = null;
        finaleEliminations = 0;
        finaleBorderTarget = 0;
        finaleStartedAt = 0;
        lastEliminated = null;
        lastShrinkAt = 0;
        finaleStartSize = 0;
        finaleShrinksDone = 0;
        if (returnGrace != null) returnGrace.clear();
        testParticipants.clear();
        revivePending.addAll(eliminated);
        eliminated.clear();

        for (World w : Bukkit.getWorlds()) w.setHardcore(false);
        World main = Bukkit.getWorld(mainWorldName);
        if (main != null) {
            main.getWorldBorder().setSize(borderStartSize);
            lastCountedDay = main.getFullTime() / 24000L;
        }
        saveData();
        getLogger().info("Tijdlijn gereset naar week 1.");
        for (Player p : Bukkit.getOnlinePlayers()) revive(p);
    }

    // ---------- Voor ReturnGrace ----------

    /** Finale bezig (en nog geen winnaar). */
    boolean finaleRunning() {
        return finaleStarted && !seasonEnded && winner == null;
    }

    boolean isEliminated(UUID uuid) {
        return eliminated.contains(uuid);
    }

    String mainWorldName() {
        return mainWorldName;
    }

    int graceMinutes() {
        return graceMinutes;
    }

    private String formatNumber(double value) {
        if (value == Math.floor(value)) {
            return String.valueOf((long) value);
        }
        return String.format("%.1f", value);
    }
}
