package com.example.lobbygames;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 1-tegen-1 duels in de lobby-arena.
 *
 * Bij de start worden de eigen spullen, levens, XP, gamemode en plek van beide
 * spelers opgeborgen (ook op schijf, in duel-saves.yml, voor als de server crasht),
 * en krijgen ze dezelfde kit. Alleen schade van je tegenstander telt. Bij 0 levens
 * gaat niemand echt dood: het duel is voorbij en iedereen krijgt alles terug.
 * Dat is belangrijk omdat de lobby in de finale ook hardcore is.
 */
final class Duels implements TabExecutor, Listener {

    private static final class Match {
        final UUID a;
        final UUID b;
        boolean fighting;   // na het aftellen
        boolean ending;     // afgelopen, wacht op terugzetten
        BukkitTask timer;

        Match(UUID a, UUID b) {
            this.a = a;
            this.b = b;
        }

        boolean has(UUID u) {
            return a.equals(u) || b.equals(u);
        }

        UUID other(UUID u) {
            return a.equals(u) ? b : a;
        }
    }

    private static final class Request {
        final UUID from;
        final UUID to;
        BukkitTask expiry;

        Request(UUID from, UUID to) {
            this.from = from;
            this.to = to;
        }
    }

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;      // spawns, kit, stats
    private final File savesFile;
    private final YamlConfiguration saves;     // opgeborgen spullen per speler

    private final int requestSeconds;
    private final int countdownSeconds;
    private final int maxSeconds;
    private final int endDelaySeconds;

    private Location spawn1;
    private Location spawn2;
    private Location pos1;   // hoeken van de arena (optioneel)
    private Location pos2;
    private Match match;
    private final Map<UUID, Request> requests = new LinkedHashMap<>();   // per aanvrager
    private final Deque<UUID> queue = new ArrayDeque<>();                 // wachtrij (losse spelers)
    private final Deque<UUID[]> pairs = new ArrayDeque<>();               // paren die wachten op de arena
    private final Set<UUID> ownTeleport = new HashSet<>();

    Duels(JavaPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "duels.yml");
        data = YamlConfiguration.loadConfiguration(file);
        savesFile = new File(plugin.getDataFolder(), "duel-saves.yml");
        saves = YamlConfiguration.loadConfiguration(savesFile);
        requestSeconds = Math.max(10, plugin.getConfig().getInt("duel.request-seconds", 60));
        countdownSeconds = Math.max(0, plugin.getConfig().getInt("duel.countdown-seconds", 3));
        maxSeconds = Math.max(30, plugin.getConfig().getInt("duel.max-seconds", 300));
        endDelaySeconds = Math.max(0, plugin.getConfig().getInt("duel.end-delay-seconds", 3));
        spawn1 = LobbyGames.loadLocation(data.getConfigurationSection("spawn1"));
        spawn2 = LobbyGames.loadLocation(data.getConfigurationSection("spawn2"));
        pos1 = LobbyGames.loadLocation(data.getConfigurationSection("pos1"));
        pos2 = LobbyGames.loadLocation(data.getConfigurationSection("pos2"));

        // Na een crash: wie online is en nog spullen heeft opgeborgen, krijgt ze terug.
        for (Player p : Bukkit.getOnlinePlayers()) restoreIfSaved(p);
    }

    String describe() {
        return spawn1 != null && spawn2 != null ? "arena ingesteld" : "arena nog niet ingesteld";
    }

    private boolean arenaReady() {
        return spawn1 != null && spawn2 != null && spawn1.getWorld() != null && spawn2.getWorld() != null;
    }

    private boolean inMatch(UUID uuid) {
        return match != null && match.has(uuid);
    }

    private void saveData() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Kon duels.yml niet opslaan: " + e.getMessage());
        }
    }

    private void saveSaves() {
        try {
            saves.save(savesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Kon duel-saves.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Spullen opbergen en teruggeven ----------

    private static List<String> encode(ItemStack[] items) {
        List<String> list = new ArrayList<>();
        for (ItemStack item : items) {
            list.add(item == null || item.getType().isAir() ? "" : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        return list;
    }

    private static ItemStack[] decode(List<String> list) {
        ItemStack[] items = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s == null || s.isEmpty()) continue;
            try {
                items[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(s));
            } catch (RuntimeException ignored) {
                items[i] = null;
            }
        }
        return items;
    }

    /** Alles van de speler opbergen (op schijf), daarna leeg maken. */
    private void store(Player p) {
        ConfigurationSection s = saves.createSection(p.getUniqueId().toString());
        s.set("name", p.getName());
        s.set("items", encode(p.getInventory().getContents()));
        s.set("level", p.getLevel());
        s.set("exp", (double) p.getExp());
        s.set("health", p.getHealth());
        s.set("food", p.getFoodLevel());
        s.set("saturation", (double) p.getSaturation());
        s.set("gamemode", p.getGameMode().name());
        LobbyGames.saveLocation(s, "location", p.getLocation());
        saveSaves();

        p.getInventory().clear();
        for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
        p.setFireTicks(0);
    }

    /** Spullen, levens enz. teruggeven. @param teleport ook terug naar de oude plek */
    private void restore(Player p, boolean teleport) {
        ConfigurationSection s = saves.getConfigurationSection(p.getUniqueId().toString());
        if (s == null) return;
        p.getInventory().clear();
        p.getInventory().setContents(decode(s.getStringList("items")));
        p.setLevel(s.getInt("level"));
        p.setExp((float) s.getDouble("exp"));
        for (PotionEffect effect : p.getActivePotionEffects()) p.removePotionEffect(effect.getType());
        p.setFireTicks(0);
        p.setHealth(Math.max(1.0, Math.min(p.getMaxHealth(), s.getDouble("health", 20.0))));
        p.setFoodLevel(s.getInt("food", 20));
        p.setSaturation((float) s.getDouble("saturation", 5.0));
        try {
            p.setGameMode(GameMode.valueOf(s.getString("gamemode", "SURVIVAL")));
        } catch (IllegalArgumentException ignored) {
            // laten zoals het is
        }
        Location back = LobbyGames.loadLocation(s.getConfigurationSection("location"));
        saves.set(p.getUniqueId().toString(), null);
        saveSaves();
        if (teleport && back != null) {
            ownTeleport.add(p.getUniqueId());
            p.teleport(back);
            ownTeleport.remove(p.getUniqueId());
        }
    }

    private void restoreIfSaved(Player p) {
        if (saves.contains(p.getUniqueId().toString()) && !inMatch(p.getUniqueId())) {
            restore(p, true);
            p.sendMessage(ChatColor.YELLOW + "Je hebt je spullen van het duel teruggekregen.");
        }
    }

    // ---------- Kit ----------

    private ItemStack[] kit() {
        List<String> stored = data.getStringList("kit");
        if (!stored.isEmpty()) return decode(stored);
        // Standaard-kit: ijzer.
        ItemStack[] items = new ItemStack[41];
        items[0] = new ItemStack(Material.IRON_SWORD);
        items[1] = new ItemStack(Material.BOW);
        items[2] = new ItemStack(Material.COOKED_BEEF, 8);
        items[3] = new ItemStack(Material.GOLDEN_APPLE, 2);
        items[9] = new ItemStack(Material.ARROW, 16);
        items[36] = new ItemStack(Material.IRON_BOOTS);
        items[37] = new ItemStack(Material.IRON_LEGGINGS);
        items[38] = new ItemStack(Material.IRON_CHESTPLATE);
        items[39] = new ItemStack(Material.IRON_HELMET);
        items[40] = new ItemStack(Material.SHIELD);
        return items;
    }

    // ---------- Duel starten ----------

    private String canDuel(Player p) {
        if (!arenaReady()) return "De duel-arena is nog niet ingesteld.";
        if (p.getWorld() != spawn1.getWorld()) return "Duels kan je alleen vanuit de lobby doen.";
        if (p.getGameMode() == GameMode.SPECTATOR) return "Als toeschouwer kan je niet duelleren.";
        if (inMatch(p.getUniqueId())) return "Je bent al in een duel.";
        return null;
    }

    /** Volgende paar de arena in, als die vrij is. */
    private void tryStart() {
        if (match != null) return;
        while (!pairs.isEmpty()) {
            UUID[] pair = pairs.poll();
            Player a = Bukkit.getPlayer(pair[0]);
            Player b = Bukkit.getPlayer(pair[1]);
            if (a == null || b == null || canDuel(a) != null || canDuel(b) != null) {
                if (a != null) a.sendMessage(ChatColor.RED + "Je duel gaat niet door (de ander is weg of kan niet).");
                if (b != null) b.sendMessage(ChatColor.RED + "Je duel gaat niet door (de ander is weg of kan niet).");
                continue;
            }
            begin(a, b);
            return;
        }
        // Wachtrij: twee losse spelers = een paar.
        while (queue.size() >= 2) {
            UUID a = queue.poll();
            UUID b = queue.poll();
            pairs.add(new UUID[]{a, b});
            tryStart();
            return;
        }
    }

    private void addPair(Player a, Player b) {
        queue.remove(a.getUniqueId());
        queue.remove(b.getUniqueId());
        pairs.add(new UUID[]{a.getUniqueId(), b.getUniqueId()});
        if (match != null) {
            a.sendMessage(ChatColor.YELLOW + "De arena is bezet; jullie zijn de volgende (plek " + pairs.size() + ").");
            b.sendMessage(ChatColor.YELLOW + "De arena is bezet; jullie zijn de volgende (plek " + pairs.size() + ").");
        }
        tryStart();
    }

    private void begin(Player a, Player b) {
        match = new Match(a.getUniqueId(), b.getUniqueId());

        for (Player p : new Player[]{a, b}) {
            store(p);
            p.getInventory().setContents(kit());
            p.setGameMode(GameMode.ADVENTURE);
            p.setHealth(p.getMaxHealth());
            p.setFoodLevel(20);
            p.setSaturation(10f);
            p.setFallDistance(0);
        }
        ownTeleport.add(a.getUniqueId());
        ownTeleport.add(b.getUniqueId());
        a.teleport(spawn1);
        b.teleport(spawn2);
        ownTeleport.remove(a.getUniqueId());
        ownTeleport.remove(b.getUniqueId());

        broadcastLobby(ChatColor.GOLD + "Duel: " + ChatColor.WHITE + a.getName() + ChatColor.GOLD + " tegen "
                + ChatColor.WHITE + b.getName() + ChatColor.GOLD + "!");

        Match m = match;
        int[] count = {countdownSeconds};
        m.timer = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (match != m) return;
            if (count[0] > 0) {
                showBoth(m, "&e&l" + count[0], "&7Maak je klaar...", Sound.BLOCK_NOTE_BLOCK_HAT);
                count[0]--;
                return;
            }
            m.timer.cancel();
            m.fighting = true;
            showBoth(m, "&c&lVECHT!", "&7Laatste met levens wint", Sound.ENTITY_ENDER_DRAGON_GROWL);
            // Maximale duur.
            m.timer = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (match == m && !m.ending) end(m, null);
            }, maxSeconds * 20L);
        }, 0L, 20L);
    }

    private void showBoth(Match m, String title, String subtitle, Sound sound) {
        Title t = Title.title(LobbyGames.legacy(title), LobbyGames.legacy(subtitle),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200)));
        for (UUID u : new UUID[]{m.a, m.b}) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) continue;
            p.showTitle(t);
            p.playSound(p.getLocation(), sound, 0.8f, 1.0f);
        }
    }

    // ---------- Duel beëindigen ----------

    /** @param winner null = gelijkspel */
    private void end(Match m, UUID winner) {
        if (m.ending) return;
        m.ending = true;
        m.fighting = false;
        if (m.timer != null) m.timer.cancel();

        Player a = Bukkit.getPlayer(m.a);
        Player b = Bukkit.getPlayer(m.b);
        String nameA = a != null ? a.getName() : saves.getString(m.a + ".name", "?");
        String nameB = b != null ? b.getName() : saves.getString(m.b + ".name", "?");

        if (winner == null) {
            showBoth(m, "&e&lGELIJKSPEL", "&7De tijd is om", Sound.BLOCK_NOTE_BLOCK_BASS);
            broadcastLobby(ChatColor.GRAY + "Duel " + nameA + " tegen " + nameB + ": gelijkspel.");
        } else {
            UUID loser = m.other(winner);
            Player w = Bukkit.getPlayer(winner);
            Player l = Bukkit.getPlayer(loser);
            String winnerName = winner.equals(m.a) ? nameA : nameB;
            String loserName = winner.equals(m.a) ? nameB : nameA;
            addStat(winner, winnerName, "wins");
            addStat(loser, loserName, "losses");
            if (w != null) {
                w.showTitle(Title.title(LobbyGames.legacy("&a&lGEWONNEN!"), LobbyGames.legacy("&7tegen " + loserName),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
                w.playSound(w.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            }
            if (l != null) {
                l.showTitle(Title.title(LobbyGames.legacy("&c&lVERLOREN"), LobbyGames.legacy("&7" + winnerName + " wint"),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
                l.playSound(l.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.8f, 1.0f);
            }
            broadcastLobby(ChatColor.GOLD + winnerName + ChatColor.YELLOW + " heeft het duel tegen "
                    + ChatColor.GOLD + loserName + ChatColor.YELLOW + " gewonnen!");
        }

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID u : new UUID[]{m.a, m.b}) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) restore(p, true);
            }
            if (match == m) match = null;
            tryStart();
        }, Math.max(1, endDelaySeconds * 20L));
    }

    private void addStat(UUID uuid, String name, String key) {
        data.set("stats." + uuid + ".name", name);
        data.set("stats." + uuid + "." + key, data.getInt("stats." + uuid + "." + key) + 1);
        saveData();
    }

    private void broadcastLobby(String message) {
        if (spawn1 == null || spawn1.getWorld() == null) return;
        for (Player p : spawn1.getWorld().getPlayers()) p.sendMessage(message);
    }

    void shutdown() {
        // Server stopt midden in een duel: iedereen alles terug.
        if (match != null) {
            for (UUID u : new UUID[]{match.a, match.b}) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) restore(p, true);
            }
            match = null;
        }
    }

    // ---------- Gevecht ----------

    private static Player attacker(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            return (Player) ((Projectile) damager).getShooter();
        }
        return null;
    }

    /** Laatst: alleen schade van je tegenstander telt, en niemand gaat echt dood. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        Player attacker = event instanceof EntityDamageByEntityEvent ? attacker(((EntityDamageByEntityEvent) event).getDamager()) : null;
        boolean victimIn = event.getEntity() instanceof Player && inMatch(event.getEntity().getUniqueId());

        if (!victimIn) {
            // Duellisten slaan geen toeschouwers.
            if (attacker != null && inMatch(attacker.getUniqueId())) event.setCancelled(true);
            return;
        }
        Player victim = (Player) event.getEntity();
        boolean fromOpponent = attacker != null && match.fighting && match.other(victim.getUniqueId()).equals(attacker.getUniqueId());
        // De lobby staat schade/pvp uit (OoitWorlds); voor het duel weer aan.
        event.setCancelled(!fromOpponent);
        if (!fromOpponent) return;

        if (victim.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            victim.setHealth(victim.getMaxHealth());
            end(match, attacker.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (inMatch(event.getEntity().getUniqueId()) && !match.fighting) event.setCancelled(true);
    }

    /** Ligt deze plek in de arena (tussen pos1 en pos2, alle hoogtes)? Geen hoeken gezet = overal. */
    private boolean inArena(Location loc) {
        if (pos1 == null || pos2 == null) return true;
        if (loc.getWorld() != pos1.getWorld()) return false;
        double minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        double maxX = Math.max(pos1.getBlockX(), pos2.getBlockX()) + 1;
        double minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        double maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ()) + 1;
        return loc.getX() >= minX && loc.getX() <= maxX && loc.getZ() >= minZ && loc.getZ() <= maxZ;
    }

    /** Aftellen: stilstaan. Vechten: niet de arena uit. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!inMatch(event.getPlayer().getUniqueId()) || match.ending) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() == to.getX() && from.getZ() == to.getZ()) return;
        if (!match.fighting) {
            event.setTo(new Location(from.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            return;
        }
        if (!inArena(to) && inArena(from)) {
            event.setTo(new Location(from.getWorld(), from.getX(), from.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            event.getPlayer().sendActionBar(LobbyGames.legacy("&cJe kunt de arena niet uit!"));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (inMatch(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (inMatch(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!inMatch(event.getPlayer().getUniqueId())) return;
        String cmd = event.getMessage().toLowerCase(Locale.ROOT);
        if (cmd.startsWith("/duel") || cmd.startsWith("/duels")) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Tijdens een duel kan je geen commando's gebruiken. Opgeven: /duel opgeven");
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        UUID u = event.getPlayer().getUniqueId();
        if (!inMatch(u) || ownTeleport.contains(u)) return;
        PlayerTeleportEvent.TeleportCause cause = event.getCause();
        if (cause == PlayerTeleportEvent.TeleportCause.ENDER_PEARL && event.getTo() != null && inArena(event.getTo())) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID u = p.getUniqueId();
        queue.remove(u);
        pairs.removeIf(pair -> pair[0].equals(u) || pair[1].equals(u));
        requests.values().removeIf(r -> {
            if (r.from.equals(u) || r.to.equals(u)) {
                r.expiry.cancel();
                return true;
            }
            return false;
        });
        if (inMatch(u)) {
            Match m = match;
            if (!m.ending) {
                broadcastLobby(ChatColor.GRAY + p.getName() + " is uitgelogd tijdens het duel.");
                end(m, m.other(u));
            }
            // Spullen meteen terug; de oude plek volgt bij het volgende inloggen (zie onJoin).
            Location back = LobbyGames.loadLocation(saves.getConfigurationSection(u + ".location"));
            restore(p, false);
            if (back != null) {
                LobbyGames.saveLocation(saves, "returns." + u, back);
                saveSaves();
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            restoreIfSaved(p); // na een crash: spullen + plek
            Location back = LobbyGames.loadLocation(saves.getConfigurationSection("returns." + p.getUniqueId()));
            if (saves.contains("returns." + p.getUniqueId())) {
                saves.set("returns." + p.getUniqueId(), null);
                saveSaves();
                if (back != null) {
                    ownTeleport.add(p.getUniqueId());
                    p.teleport(back);
                    ownTeleport.remove(p.getUniqueId());
                }
            }
        }, 5L);
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        Player p = sender instanceof Player ? (Player) sender : null;

        switch (sub) {
            case "help":
                help(sender);
                return true;
            case "top":
                top(sender);
                return true;
            case "pos1":
            case "pos2":
            case "setspawn1":
            case "setspawn2":
            case "setkit":
            case "resetkit":
            case "info":
                admin(sender, sub);
                return true;
            default:
                break;
        }
        if (p == null) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }

        switch (sub) {
            case "accept":
            case "deny":
                answer(p, args.length > 1 ? args[1] : null, sub.equals("accept"));
                return true;
            case "wachtrij":
            case "queue": {
                if (queue.contains(p.getUniqueId())) {
                    queue.remove(p.getUniqueId());
                    p.sendMessage(ChatColor.GRAY + "Je staat niet meer in de wachtrij.");
                    return true;
                }
                String problem = canDuel(p);
                if (problem != null) {
                    p.sendMessage(ChatColor.RED + problem);
                    return true;
                }
                queue.add(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Je staat in de duel-wachtrij. Zodra er een tegenstander is, begint het."
                        + ChatColor.GRAY + " (nog eens = eruit)");
                tryStart();
                return true;
            }
            case "verlaat":
            case "leave":
                if (queue.remove(p.getUniqueId())) p.sendMessage(ChatColor.GRAY + "Je staat niet meer in de wachtrij.");
                else p.sendMessage(ChatColor.RED + "Je staat niet in de wachtrij.");
                return true;
            case "opgeven":
                if (!inMatch(p.getUniqueId()) || match.ending) {
                    p.sendMessage(ChatColor.RED + "Je zit niet in een duel.");
                    return true;
                }
                broadcastLobby(ChatColor.GRAY + p.getName() + " heeft het duel opgegeven.");
                end(match, match.other(p.getUniqueId()));
                return true;
            default:
                request(p, args[0]);
                return true;
        }
    }

    private void request(Player p, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            p.sendMessage(ChatColor.RED + "Speler '" + targetName + "' is niet online. Zie /duel help.");
            return;
        }
        if (target.equals(p)) {
            p.sendMessage(ChatColor.RED + "Je kunt jezelf niet uitdagen.");
            return;
        }
        String problem = canDuel(p);
        if (problem == null && canDuel(target) != null) problem = target.getName() + " kan nu niet duelleren.";
        if (problem != null) {
            p.sendMessage(ChatColor.RED + problem);
            return;
        }
        Request old = requests.remove(p.getUniqueId());
        if (old != null) old.expiry.cancel();
        Request req = new Request(p.getUniqueId(), target.getUniqueId());
        req.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (requests.get(req.from) != req) return;
            requests.remove(req.from);
            p.sendMessage(ChatColor.GRAY + "Je duel-uitdaging aan " + target.getName() + " is verlopen.");
        }, requestSeconds * 20L);
        requests.put(p.getUniqueId(), req);

        p.sendMessage(ChatColor.GREEN + "Uitdaging verstuurd naar " + target.getName() + ".");
        target.sendMessage(LobbyGames.legacy("&6" + p.getName() + " &edaagt je uit voor een duel!"));
        target.sendMessage(Component.text()
                .append(button("[Accepteren]", NamedTextColor.GREEN, "/duel accept " + p.getName()))
                .append(Component.text("  "))
                .append(button("[Weigeren]", NamedTextColor.RED, "/duel deny " + p.getName()))
                .append(Component.text("  (" + requestSeconds + " s)", NamedTextColor.DARK_GRAY))
                .build());
        target.playSound(target.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 0.6f, 1.0f);
    }

    private static Component button(String text, NamedTextColor color, String cmd) {
        return Component.text(text, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(cmd))
                .hoverEvent(HoverEvent.showText(Component.text("Klik", NamedTextColor.GRAY)));
    }

    private void answer(Player p, String fromName, boolean accept) {
        Request found = null;
        for (Request r : requests.values()) {
            if (!r.to.equals(p.getUniqueId())) continue;
            Player from = Bukkit.getPlayer(r.from);
            if (fromName == null || (from != null && from.getName().equalsIgnoreCase(fromName))) found = r;
        }
        if (found == null) {
            p.sendMessage(ChatColor.RED + "Je hebt geen openstaande duel-uitdaging" + (fromName != null ? " van " + fromName : "") + ".");
            return;
        }
        requests.remove(found.from);
        found.expiry.cancel();
        Player from = Bukkit.getPlayer(found.from);
        if (from == null) {
            p.sendMessage(ChatColor.RED + "Die speler is niet meer online.");
            return;
        }
        if (!accept) {
            p.sendMessage(ChatColor.GRAY + "Uitdaging van " + from.getName() + " geweigerd.");
            from.sendMessage(ChatColor.RED + p.getName() + " heeft je duel-uitdaging geweigerd.");
            return;
        }
        String problem = canDuel(p);
        if (problem == null && canDuel(from) != null) problem = from.getName() + " kan nu niet duelleren.";
        if (problem != null) {
            p.sendMessage(ChatColor.RED + problem);
            return;
        }
        addPair(from, p);
    }

    private void admin(CommandSender sender, String sub) {
        if (!sender.hasPermission("lobbygames.admin")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return;
        }
        Player p = sender instanceof Player ? (Player) sender : null;
        switch (sub) {
            case "setspawn1":
            case "setspawn2": {
                if (p == null) return;
                Location loc = LobbyGames.blockCenter(p.getLocation());
                if (sub.equals("setspawn1")) spawn1 = loc;
                else spawn2 = loc;
                LobbyGames.saveLocation(data, sub.equals("setspawn1") ? "spawn1" : "spawn2", loc);
                saveData();
                p.sendMessage(ChatColor.GREEN + "Duel-startplek " + sub.charAt(sub.length() - 1) + " gezet (kijkrichting telt mee)."
                        + (arenaReady() ? " De arena is klaar!" : ""));
                return;
            }
            case "pos1":
            case "pos2": {
                if (p == null) return;
                Location corner = p.getLocation().getBlock().getLocation();
                if (sub.equals("pos1")) pos1 = corner;
                else pos2 = corner;
                LobbyGames.saveLocation(data, sub, corner);
                saveData();
                p.sendMessage(ChatColor.GREEN + "Arena-hoek " + sub.charAt(3) + " gezet (" + corner.getBlockX() + ", " + corner.getBlockZ() + ")."
                        + (pos1 != null && pos2 != null ? " De arena is nu afgebakend: tijdens een duel kan niemand eruit." : " Zet nu ook de andere hoek."));
                if (pos1 != null && pos2 != null && ((spawn1 != null && !inArena(spawn1)) || (spawn2 != null && !inArena(spawn2)))) {
                    p.sendMessage(ChatColor.YELLOW + "Let op: een startplek ligt buiten de arena. Zet de startplekken opnieuw.");
                }
                return;
            }
            case "setkit":
                if (p == null) return;
                data.set("kit", encode(p.getInventory().getContents()));
                saveData();
                p.sendMessage(ChatColor.GREEN + "Kit opgeslagen: precies wat je nu in je inventory hebt (ook harnas en tweede hand).");
                return;
            case "resetkit":
                data.set("kit", null);
                saveData();
                sender.sendMessage(ChatColor.GREEN + "Kit terug naar de standaard (ijzer).");
                return;
            default:
                sender.sendMessage(ChatColor.GOLD + "=== Duels ===");
                sender.sendMessage(ChatColor.GRAY + "Arena: " + ChatColor.WHITE + describe());
                sender.sendMessage(ChatColor.GRAY + "Arena-grens: " + ChatColor.WHITE + (pos1 != null && pos2 != null
                        ? "x " + Math.min(pos1.getBlockX(), pos2.getBlockX()) + " t/m " + Math.max(pos1.getBlockX(), pos2.getBlockX())
                        + ", z " + Math.min(pos1.getBlockZ(), pos2.getBlockZ()) + " t/m " + Math.max(pos1.getBlockZ(), pos2.getBlockZ())
                        : "geen (zet met /duel pos1 en pos2)"));
                sender.sendMessage(ChatColor.GRAY + "Kit: " + ChatColor.WHITE + (data.getStringList("kit").isEmpty() ? "standaard (ijzer)" : "eigen kit"));
                sender.sendMessage(ChatColor.GRAY + "Bezig: " + ChatColor.WHITE + (match == null ? "nee" : "ja")
                        + ChatColor.GRAY + ", wachtrij: " + ChatColor.WHITE + queue.size() + ChatColor.GRAY + ", wachtende duels: "
                        + ChatColor.WHITE + pairs.size());
        }
    }

    private void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Duels ===");
        sender.sendMessage(ChatColor.YELLOW + "/duel <speler>" + ChatColor.GRAY + " - iemand uitdagen");
        sender.sendMessage(ChatColor.YELLOW + "/duel accept|deny [speler]" + ChatColor.GRAY + " - uitdaging aannemen/weigeren");
        sender.sendMessage(ChatColor.YELLOW + "/duel wachtrij" + ChatColor.GRAY + " - in de wachtrij voor een willekeurige tegenstander");
        sender.sendMessage(ChatColor.YELLOW + "/duel opgeven" + ChatColor.GRAY + " - stoppen tijdens een duel");
        sender.sendMessage(ChatColor.YELLOW + "/duel top" + ChatColor.GRAY + " - meeste overwinningen");
        if (sender.hasPermission("lobbygames.admin")) {
            sender.sendMessage(ChatColor.GOLD + "Beheer:");
            sender.sendMessage(ChatColor.YELLOW + "/duel setspawn1 | setspawn2" + ChatColor.GRAY + " - startplekken in de arena (waar je staat)");
            sender.sendMessage(ChatColor.YELLOW + "/duel pos1 | pos2" + ChatColor.GRAY + " - twee tegenover elkaar liggende hoeken: niemand kan de arena uit");
            sender.sendMessage(ChatColor.YELLOW + "/duel setkit | resetkit | info" + ChatColor.GRAY + " - kit = je huidige inventory");
        }
    }

    private void top(CommandSender sender) {
        ConfigurationSection stats = data.getConfigurationSection("stats");
        if (stats == null || stats.getKeys(false).isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Er is nog geen duel gespeeld.");
            return;
        }
        List<String> keys = new ArrayList<>(stats.getKeys(false));
        keys.sort((x, y) -> Integer.compare(stats.getInt(y + ".wins"), stats.getInt(x + ".wins")));
        sender.sendMessage(ChatColor.GOLD + "=== Duel-ranglijst ===");
        for (int i = 0; i < Math.min(10, keys.size()); i++) {
            String k = keys.get(i);
            sender.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + ChatColor.WHITE + stats.getString(k + ".name", "?")
                    + ChatColor.GRAY + " - " + stats.getInt(k + ".wins") + " gewonnen, " + stats.getInt(k + ".losses") + " verloren");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(Arrays.asList("wachtrij", "accept", "deny", "opgeven", "top", "help"));
            if (sender.hasPermission("lobbygames.admin")) options.addAll(Arrays.asList("setspawn1", "setspawn2", "pos1", "pos2", "setkit", "resetkit", "info"));
            for (Player p : Bukkit.getOnlinePlayers()) if (!p.equals(sender)) options.add(p.getName());
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("accept") || args[0].equalsIgnoreCase("deny"))) {
            for (Request r : requests.values()) {
                Player from = Bukkit.getPlayer(r.from);
                if (from != null && sender instanceof Player && r.to.equals(((Player) sender).getUniqueId())) options.add(from.getName());
            }
        } else {
            return Collections.emptyList();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(typed)) result.add(o);
        return result;
    }
}
