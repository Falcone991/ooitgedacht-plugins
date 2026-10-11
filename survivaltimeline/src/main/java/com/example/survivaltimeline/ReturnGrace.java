package com.example.survivaltimeline;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Tijdens de finale: wie terugkomt in survival en buiten de (intussen gekrompen)
 * grens staat, krijgt een half uur om naar de grens te lopen zonder grensschade.
 *
 *  - De timer start pas als de speler beweegt.
 *  - De weg naar de grens (richting, afstand, coördinaten) staat steeds onderin beeld.
 *  - Wie de verkeerde kant op loopt, krijgt schade zoals bij honger (een half hartje per 4 s).
 *  - Binnen de grens = klaar. Tijd op = gewone grensschade.
 *
 * De resterende tijd wordt bewaard bij uitloggen (grace.yml), dus opnieuw inloggen
 * zet de timer niet terug. Wie survival verlaat terwijl die al buiten de grens stond
 * (zonder hulp-tijd), krijgt bij terugkomst geen nieuwe tijd: hulp is er alleen als
 * de grens over je heen schoof terwijl je weg was.
 */
final class ReturnGrace implements Listener {

    private static final long DAMAGE_EVERY_MS = 4000L;   // zoals honger
    private static final double WRONG_WAY_MARGIN = 3.0;   // zoveel blokken verder weg mag, daarna schade
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final class Session {
        long remainingMs;
        boolean running;
        double best;
        long lastDamage;
    }

    private final SurvivalTimeline plugin;
    private final File file;
    private final YamlConfiguration saved;   // uuid -> resterende ms (0 = geen hulp meer); ontbreekt = volle tijd
    private final Map<UUID, Session> sessions = new HashMap<>();

    ReturnGrace(SurvivalTimeline plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "grace.yml");
        this.saved = YamlConfiguration.loadConfiguration(file);
    }

    private long fullMs() {
        return TimeUnit.MINUTES.toMillis(plugin.graceMinutes());
    }

    private void save() {
        try {
            saved.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Kon grace.yml niet opslaan: " + e.getMessage());
        }
    }

    /** Doet deze speler mee in survival (geen toeschouwer/creative, niet uitgeschakeld, finale bezig)? */
    private boolean applies(Player p) {
        return plugin.finaleRunning() && plugin.graceMinutes() > 0
                && p.getWorld().getName().equals(plugin.mainWorldName())
                && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE
                && !plugin.isEliminated(p.getUniqueId());
    }

    /** Hoe ver buiten de grens (0 = erbinnen). */
    private static double outside(Location loc) {
        WorldBorder border = loc.getWorld().getWorldBorder();
        double half = border.getSize() / 2.0;
        Location c = border.getCenter();
        double dx = Math.abs(loc.getX() - c.getX()) - half;
        double dz = Math.abs(loc.getZ() - c.getZ()) - half;
        return Math.max(0, Math.max(dx, dz));
    }

    // ---------- Starten (inloggen of survival in komen) ----------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        // Meteen: de grens doet al in de eerste tick schade (ver buiten de grens = direct dood).
        start(p);
        // En nog eens als andere plugins (bv. de lobby) de speler intussen verplaatst hebben.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) start(p);
        }, 15L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        start(event.getPlayer()); // meteen, vóór de grens schade kan doen
    }

    private void start(Player p) {
        UUID uuid = p.getUniqueId();
        if (sessions.containsKey(uuid) || !applies(p)) return;
        double d = outside(p.getLocation());
        if (d <= 0) {
            if (saved.contains(uuid.toString())) {
                saved.set(uuid.toString(), null); // weer binnen: volgende keer weer de volle tijd
                save();
            }
            return;
        }
        long left = saved.getLong(uuid.toString(), fullMs());
        if (left <= 0) {
            p.sendMessage(ChatColor.RED + "Je staat buiten de wereldgrens en je hulp-tijd is op. Zorg dat je snel binnen komt!");
            return;
        }

        Session s = new Session();
        s.remainingMs = left;
        s.best = d;
        sessions.put(uuid, s);

        // Buiten de grens kun je niets breken. Zit je binnen of onder de grond, dan zou je
        // vastzitten: daarom recht omhoog naar het oppervlak (zelfde x/z, dus niet dichterbij).
        boolean lifted = liftToSurface(p);

        p.showTitle(Title.title(LEGACY.deserialize("&c&lBuiten de wereldgrens!"),
                LEGACY.deserialize("&eLoop naar de grens, je hebt " + minutes(left) + " zonder schade"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofSeconds(1))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.0f, 0.5f);
        p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Je staat buiten de wereldgrens! " + ChatColor.YELLOW
                + "Terwijl je weg was is de grens gekrompen.");
        p.sendMessage(ChatColor.YELLOW + "Je hebt " + minutes(left) + " om naar de grens te lopen zonder schade van de grens."
                + " De tijd begint zodra je beweegt. Loop je de verkeerde kant op, dan krijg je schade.");
        p.sendMessage(ChatColor.GRAY + "De weg staat onderin je scherm. " + directions(p.getLocation()));
        if (lifted) {
            p.sendMessage(ChatColor.GRAY + "Je zat binnen of onder de grond, en buiten de grens kun je niets breken."
                    + " Daarom ben je recht omhoog naar het oppervlak gezet.");
        }
    }

    /** Staat er iets boven de speler (dak, grot, mijn)? Dan recht omhoog op het oppervlak zetten. */
    private static boolean liftToSurface(Player p) {
        Location loc = p.getLocation();
        World world = loc.getWorld();
        int x = loc.getBlockX();
        int z = loc.getBlockZ();
        // Bladeren tellen niet mee: onder een boom kun je gewoon weglopen.
        int top = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (loc.getBlockY() > top) return false;
        Location surface = new Location(world, x + 0.5, top + 1, z + 0.5, loc.getYaw(), loc.getPitch());
        p.setFallDistance(0);
        p.teleport(surface);
        return true;
    }

    // ---------- Elke seconde ----------

    void tick() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Session> entry : new HashMap<>(sessions).entrySet()) {
            UUID uuid = entry.getKey();
            Session s = entry.getValue();
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                sessions.remove(uuid);
                continue;
            }
            if (!applies(p)) {
                stop(p, s);
                continue;
            }

            double d = outside(p.getLocation());
            if (d <= 0) {
                sessions.remove(uuid);
                saved.set(uuid.toString(), null);
                save();
                p.showTitle(Title.title(LEGACY.deserialize("&a&lBinnen de grens!"), LEGACY.deserialize("&7Succes in de finale"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofSeconds(1))));
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
                continue;
            }

            if (s.running) {
                s.remainingMs -= 1000L;
                if (s.remainingMs <= 0) {
                    sessions.remove(uuid);
                    saved.set(uuid.toString(), 0);
                    save();
                    p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "Je tijd is om! " + ChatColor.RED
                            + "Vanaf nu krijg je schade van de wereldgrens. " + ChatColor.GRAY + directions(p.getLocation()));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                    continue;
                }
            }

            boolean wrongWay = d > s.best + WRONG_WAY_MARGIN;
            if (wrongWay) {
                if (now - s.lastDamage >= DAMAGE_EVERY_MS) {
                    s.lastDamage = now;
                    p.damage(1.0);
                }
            } else if (d < s.best) {
                s.best = d;
            }

            String time = s.running ? minutesSeconds(s.remainingMs) : "start zodra je beweegt";
            p.sendActionBar(LEGACY.deserialize((wrongWay ? "&c&lVerkeerde kant! " : "&e")
                    + shortDirections(p.getLocation()) + " &8| &7" + time));
        }
    }

    // ---------- Timer starten, schade, stoppen ----------

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || s.running) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() && from.getBlockZ() == to.getBlockZ()) return;
        s.running = true;
        event.getPlayer().sendMessage(ChatColor.YELLOW + "De tijd loopt: " + minutes(s.remainingMs) + " om bij de grens te komen.");
    }

    /** Geen grensschade tijdens de hulp-tijd. */
    @EventHandler(ignoreCancelled = true)
    public void onBorderDamage(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.WORLD_BORDER
                && event.getEntity() instanceof Player && sessions.containsKey(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        leaving(event.getPlayer(), event.getPlayer().getLocation());
    }

    /** Survival uit (bv. /lobby): zelfde als uitloggen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == null || to == null || to.getWorld() == from.getWorld()) return;
        if (!from.getWorld().getName().equals(plugin.mainWorldName())) return;
        leaving(event.getPlayer(), from);
    }

    private void leaving(Player p, Location from) {
        UUID uuid = p.getUniqueId();
        Session s = sessions.remove(uuid);
        if (s != null) {
            saved.set(uuid.toString(), Math.max(0, s.remainingMs)); // verder waar je was
            save();
        } else if (plugin.finaleRunning() && from.getWorld() != null && from.getWorld().getName().equals(plugin.mainWorldName())
                && p.getGameMode() != GameMode.SPECTATOR && p.getGameMode() != GameMode.CREATIVE && outside(from) > 0) {
            // Al buiten de grens zonder hulp: niet even weg- en terugspringen voor nieuwe tijd.
            saved.set(uuid.toString(), 0);
            save();
        }
    }

    private void stop(Player p, Session s) {
        sessions.remove(p.getUniqueId());
        saved.set(p.getUniqueId().toString(), Math.max(0, s.remainingMs));
        save();
    }

    /** Na het seizoen of een reset: alles vergeten. */
    void clear() {
        sessions.clear();
        for (String key : saved.getKeys(false)) saved.set(key, null);
        save();
    }

    // ---------- Weg naar de grens ----------

    /** Dichtstbijzijnde plek net binnen de grens. */
    private static Location nearestInside(Location loc) {
        WorldBorder border = loc.getWorld().getWorldBorder();
        Location c = border.getCenter();
        double half = Math.max(0.5, border.getSize() / 2.0 - 2);
        double x = Math.max(c.getX() - half, Math.min(c.getX() + half, loc.getX()));
        double z = Math.max(c.getZ() - half, Math.min(c.getZ() + half, loc.getZ()));
        return new Location(loc.getWorld(), x, loc.getY(), z);
    }

    private static String compass(double dx, double dz) {
        // Noord = -z, oost = +x.
        double angle = (Math.toDegrees(Math.atan2(dx, -dz)) + 360) % 360;
        String[] names = {"noord", "noordoost", "oost", "zuidoost", "zuid", "zuidwest", "west", "noordwest"};
        return names[(int) Math.round(angle / 45.0) % 8];
    }

    private static String directions(Location loc) {
        Location target = nearestInside(loc);
        double dx = target.getX() - loc.getX();
        double dz = target.getZ() - loc.getZ();
        long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
        return "De grens is " + dist + " blokken naar het " + compass(dx, dz) + ", bij x " + Math.round(target.getX())
                + ", z " + Math.round(target.getZ()) + ".";
    }

    private static String shortDirections(Location loc) {
        Location target = nearestInside(loc);
        double dx = target.getX() - loc.getX();
        double dz = target.getZ() - loc.getZ();
        long dist = Math.round(Math.sqrt(dx * dx + dz * dz));
        return "Grens: " + dist + " blokken " + compass(dx, dz) + " &7(x " + Math.round(target.getX())
                + ", z " + Math.round(target.getZ()) + ")";
    }

    private static String minutes(long ms) {
        long min = Math.max(1, Math.round(ms / 60000.0));
        return min + (min == 1 ? " minuut" : " minuten");
    }

    private static String minutesSeconds(long ms) {
        long total = Math.max(0, ms / 1000);
        return String.format("nog %d:%02d", total / 60, total % 60);
    }
}
