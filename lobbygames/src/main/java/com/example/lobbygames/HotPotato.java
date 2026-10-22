package com.example.lobbygames;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Hot Potato (tikkertje met een bom) op een eigen eiland in de lobby.
 *
 * Spelers doen mee met /hotpotato join (of via een NPC). Zodra er genoeg zijn telt het af,
 * daarna krijgt iemand de bom (TNT boven zijn hoofd). Sla iemand om de bom door te geven.
 * Als de (geheime) tijd om is, ontploft de bom: wie hem heeft ligt eruit. Laatste over = winnaar.
 * Niemand krijgt echte schade en je eigen spullen worden niet aangeraakt.
 *
 * Beheer: /hotpotato setspawn (midden van het eiland), pos1 + pos2 (hoeken: verder dan dat kan
 * je niet), info. Opslag: hotpotato.yml (plekken en overwinningen).
 */
final class HotPotato implements TabExecutor, Listener {

    private enum State { IDLE, WAITING, PLAYING }

    private final JavaPlugin plugin;
    private final File file;
    private final YamlConfiguration data;
    private final Random random = new Random();
    private Predicate<UUID> otherGame;

    private final int minPlayers;
    private final int startCountdown;
    private final int roundMin;
    private final int roundMax;
    private final long passCooldownMs;

    private Location spawn;
    private Location pos1;
    private Location pos2;

    private State state = State.IDLE;
    private final Map<UUID, Location> returnTo = new LinkedHashMap<>(); // deelnemers + waar ze vandaan kwamen
    private final Set<UUID> alive = new HashSet<>();
    private final Set<UUID> ownTeleport = new HashSet<>();
    private UUID holder;
    private ItemDisplay bomb;
    private long lastPass;
    private UUID lastHolder;
    private int roundLeft;
    private BukkitTask task;
    private final Map<UUID, Integer> wins = new HashMap<>();
    private final Map<UUID, String> names = new HashMap<>();

    // Eigen spullen tijdens het spel, ook op schijf (voor als de server crasht)
    private final File savesFile;
    private final YamlConfiguration saves;

    HotPotato(JavaPlugin plugin) {
        this.plugin = plugin;
        file = new File(plugin.getDataFolder(), "hotpotato.yml");
        data = YamlConfiguration.loadConfiguration(file);
        savesFile = new File(plugin.getDataFolder(), "hotpotato-saves.yml");
        saves = YamlConfiguration.loadConfiguration(savesFile);
        // Na een herstart terwijl er gespeeld werd: wie nog online is, krijgt meteen alles terug.
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) restoreIfSaved(p);
        });
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("hotpotato");
        minPlayers = c == null ? 2 : Math.max(2, c.getInt("min-players", 2));
        startCountdown = c == null ? 15 : Math.max(3, c.getInt("start-countdown", 15));
        roundMin = c == null ? 15 : Math.max(5, c.getInt("round-min-seconds", 15));
        roundMax = c == null ? 30 : Math.max(roundMin, c.getInt("round-max-seconds", 30));
        passCooldownMs = c == null ? 750 : c.getLong("pass-cooldown-ms", 750);
        spawn = LobbyGames.loadLocation(data.getConfigurationSection("spawn"));
        pos1 = LobbyGames.loadLocation(data.getConfigurationSection("pos1"));
        pos2 = LobbyGames.loadLocation(data.getConfigurationSection("pos2"));
        ConfigurationSection w = data.getConfigurationSection("wins");
        if (w != null) {
            for (String key : w.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    wins.put(uuid, w.getInt(key + ".wins"));
                    names.put(uuid, w.getString(key + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                    // overslaan
                }
            }
        }
    }

    void setOtherGame(Predicate<UUID> otherGame) {
        this.otherGame = otherGame;
    }

    /** Doet deze speler mee (wachtend of spelend)? */
    boolean isIn(UUID uuid) {
        return returnTo.containsKey(uuid);
    }

    String describe() {
        return spawn == null ? "nog niet ingesteld" : "eiland ingesteld" + (pos1 != null && pos2 != null ? " met grens" : "");
    }

    void shutdown() {
        if (state != State.IDLE) stop(null);
    }

    private void save() {
        LobbyGames.saveLocation(data, "spawn", spawn);
        LobbyGames.saveLocation(data, "pos1", pos1);
        LobbyGames.saveLocation(data, "pos2", pos2);
        data.set("wins", null);
        for (Map.Entry<UUID, Integer> e : wins.entrySet()) {
            data.set("wins." + e.getKey() + ".name", names.get(e.getKey()));
            data.set("wins." + e.getKey() + ".wins", e.getValue());
        }
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Kon hotpotato.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Commando ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "join":
            case "meedoen":
                if (sender instanceof Player) join((Player) sender);
                return true;
            case "leave":
            case "stop":
                if (sender instanceof Player) {
                    Player p = (Player) sender;
                    if (!isIn(p.getUniqueId())) p.sendMessage(ChatColor.RED + "Je doet niet mee.");
                    else leave(p, true);
                }
                return true;
            case "top":
                top(sender);
                return true;
            case "setspawn":
            case "pos1":
            case "pos2":
            case "forcestop":
            case "info":
                admin(sender, sub);
                return true;
            default:
                sender.sendMessage(ChatColor.GOLD + "=== Hot Potato ===");
                sender.sendMessage(ChatColor.YELLOW + "/hotpotato join" + ChatColor.GRAY + " - meedoen (sla iemand om de bom door te geven!)");
                sender.sendMessage(ChatColor.YELLOW + "/hotpotato leave" + ChatColor.GRAY + " - stoppen");
                sender.sendMessage(ChatColor.YELLOW + "/hotpotato top" + ChatColor.GRAY + " - meeste overwinningen");
                if (sender.hasPermission("lobbygames.admin")) {
                    sender.sendMessage(ChatColor.GOLD + "Beheer: " + ChatColor.YELLOW + "/hotpotato setspawn | pos1 | pos2 | info | forcestop");
                }
                return true;
        }
    }

    private void admin(CommandSender sender, String sub) {
        if (!sender.hasPermission("lobbygames.admin")) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return;
        }
        Player p = sender instanceof Player ? (Player) sender : null;
        switch (sub) {
            case "setspawn":
                if (p == null) return;
                spawn = LobbyGames.blockCenter(p.getLocation());
                save();
                p.sendMessage(ChatColor.GREEN + "Midden van het Hot Potato-eiland gezet.");
                return;
            case "pos1":
            case "pos2":
                if (p == null) return;
                if (sub.equals("pos1")) pos1 = p.getLocation().getBlock().getLocation();
                else pos2 = p.getLocation().getBlock().getLocation();
                save();
                p.sendMessage(ChatColor.GREEN + "Hoek " + sub.charAt(3) + " gezet." + (pos1 != null && pos2 != null
                        ? " Het eiland is afgebakend: verder kan niemand tijdens het spel." : " Zet nu ook de andere hoek."));
                return;
            case "forcestop":
                if (state == State.IDLE) sender.sendMessage(ChatColor.YELLOW + "Er loopt geen spel.");
                else stop(null);
                return;
            default:
                sender.sendMessage(ChatColor.GOLD + "=== Hot Potato ===");
                sender.sendMessage(ChatColor.GRAY + "Eiland: " + ChatColor.WHITE + describe());
                sender.sendMessage(ChatColor.GRAY + "Status: " + ChatColor.WHITE + state.name().toLowerCase(Locale.ROOT)
                        + ChatColor.GRAY + ", spelers: " + ChatColor.WHITE + returnTo.size());
        }
    }

    private void top(CommandSender sender) {
        if (wins.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Nog niemand heeft Hot Potato gewonnen.");
            return;
        }
        List<Map.Entry<UUID, Integer>> list = new ArrayList<>(wins.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());
        sender.sendMessage(ChatColor.GOLD + "=== Hot Potato: meeste overwinningen ===");
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            sender.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + ChatColor.WHITE + names.get(list.get(i).getKey())
                    + ChatColor.GRAY + " - " + list.get(i).getValue());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> options = new ArrayList<>(Arrays.asList("join", "leave", "top"));
        if (sender.hasPermission("lobbygames.admin")) options.addAll(Arrays.asList("setspawn", "pos1", "pos2", "info", "forcestop"));
        List<String> result = new ArrayList<>();
        for (String o : options) if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(o);
        return result;
    }

    // ---------- Meedoen / stoppen ----------

    private void join(Player p) {
        if (spawn == null) {
            p.sendMessage(ChatColor.RED + "Het Hot Potato-eiland is nog niet ingesteld.");
            return;
        }
        if (isIn(p.getUniqueId())) {
            p.sendMessage(ChatColor.YELLOW + "Je doet al mee. Stoppen: /hotpotato leave");
            return;
        }
        if (state == State.PLAYING) {
            p.sendMessage(ChatColor.YELLOW + "Er is al een potje bezig. Wacht even tot het klaar is!");
            return;
        }
        if (p.getWorld() != spawn.getWorld()) {
            p.sendMessage(ChatColor.RED + "Hot Potato kan je alleen vanuit de lobby spelen.");
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR || (otherGame != null && otherGame.test(p.getUniqueId()))) {
            p.sendMessage(ChatColor.RED + "Je kunt nu niet meedoen (toeschouwer of al in een duel).");
            return;
        }
        returnTo.put(p.getUniqueId(), p.getLocation());
        store(p);
        teleport(p, scatter());
        p.sendMessage(ChatColor.GRAY + "Je eigen spullen zijn veilig opgeborgen; na het spel krijg je ze terug.");
        for (Player other : players()) {
            other.sendMessage(ChatColor.GOLD + p.getName() + ChatColor.YELLOW + " doet mee aan Hot Potato! ("
                    + returnTo.size() + " speler" + (returnTo.size() == 1 ? "" : "s") + ")");
        }
        if (state == State.IDLE) {
            state = State.WAITING;
            waitTick();
        }
    }

    /** Uit het spel; back = terug naar waar je vandaan kwam. */
    private void leave(Player p, boolean back) {
        Location from = returnTo.remove(p.getUniqueId());
        boolean wasAlive = alive.remove(p.getUniqueId());
        cleanup(p);
        restore(p);
        if (back && from != null) teleport(p, from);
        p.sendMessage(ChatColor.GRAY + "Je doet niet meer mee aan Hot Potato.");
        if (state == State.PLAYING && wasAlive) {
            if (p.getUniqueId().equals(holder)) {
                removeBomb();
                holder = null;
                if (!checkWinner()) nextRound();
            } else {
                checkWinner();
            }
        } else if (state == State.WAITING && returnTo.isEmpty()) {
            stop(null);
        }
    }

    private void cleanup(Player p) {
        p.removePotionEffect(PotionEffectType.SPEED);
        p.removePotionEffect(PotionEffectType.GLOWING);
    }

    // ---------- Wachten op spelers ----------

    private void waitTick() {
        final int[] left = {startCountdown};
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (returnTo.size() < minPlayers) {
                left[0] = startCountdown;
                for (Player p : players()) {
                    p.sendActionBar(LobbyGames.legacy("&eWachten op spelers... &f" + returnTo.size() + "/" + minPlayers));
                }
                return;
            }
            if (left[0] <= 0) {
                task.cancel();
                startGame();
                return;
            }
            for (Player p : players()) {
                p.sendActionBar(LobbyGames.legacy("&6Hot Potato begint over &f" + left[0] + " s &7(" + returnTo.size() + " spelers)"));
                if (left[0] <= 5) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 1f, 1f);
            }
            left[0]--;
        }, 0L, 20L);
    }

    // ---------- Het spel ----------

    private void startGame() {
        state = State.PLAYING;
        alive.clear();
        alive.addAll(returnTo.keySet());
        for (Player p : players()) {
            teleport(p, scatter());
            p.showTitle(Title.title(LobbyGames.legacy("&c&lHOT POTATO"), LobbyGames.legacy("&7Sla iemand om de bom door te geven!"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(2), Duration.ofMillis(500))));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::nextRound, 40L);
    }

    private void nextRound() {
        if (state != State.PLAYING || checkWinner()) return;
        List<UUID> list = new ArrayList<>(alive);
        giveBomb(Bukkit.getPlayer(list.get(random.nextInt(list.size()))), null);
        roundLeft = roundMin + random.nextInt(roundMax - roundMin + 1);
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::roundTick, 0L, 20L);
    }

    private void roundTick() {
        Player h = holder == null ? null : Bukkit.getPlayer(holder);
        if (h == null) return;
        if (roundLeft <= 0) {
            task.cancel();
            explode(h);
            return;
        }
        // De tijd is geheim; alleen het tikken wordt sneller en hoger.
        float pitch = roundLeft <= 3 ? 2f : roundLeft <= 6 ? 1.5f : 1f;
        for (Player p : players()) {
            p.playSound(h.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, pitch);
            if (p.equals(h)) p.sendActionBar(LobbyGames.legacy("&c&lJIJ HEBT DE BOM! &fSla iemand!"));
            else if (alive.contains(p.getUniqueId())) p.sendActionBar(LobbyGames.legacy("&e" + h.getName() + " &7heeft de bom. Rennen!"));
        }
        h.getWorld().spawnParticle(Particle.SMOKE, h.getLocation().add(0, 2.4, 0), 4, 0.1, 0.1, 0.1, 0.01);
        roundLeft--;
    }

    private void giveBomb(Player to, Player from) {
        if (to == null) return;
        Player old = holder == null ? null : Bukkit.getPlayer(holder);
        if (old != null) {
            old.getInventory().clear();
            old.removePotionEffect(PotionEffectType.GLOWING);
            old.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 0, false, false));
        }
        lastHolder = holder;
        holder = to.getUniqueId();
        lastPass = System.currentTimeMillis();
        to.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, PotionEffect.INFINITE_DURATION, 0, false, false));
        to.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, 1, false, false));
        to.showTitle(Title.title(LobbyGames.legacy("&c&lJIJ HEBT DE BOM!"), LobbyGames.legacy("&7Sla snel iemand anders!"),
                Title.Times.times(Duration.ZERO, Duration.ofMillis(1200), Duration.ofMillis(300))));
        to.playSound(to.getLocation(), Sound.ENTITY_TNT_PRIMED, 1f, 1f);
        ItemStack tnt = new ItemStack(Material.TNT);
        org.bukkit.inventory.meta.ItemMeta meta = tnt.getItemMeta();
        meta.displayName(LobbyGames.legacy("&c&lDE BOM! &7Sla iemand!")
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        tnt.setItemMeta(meta);
        for (int slot = 0; slot < 9; slot++) to.getInventory().setItem(slot, tnt);

        removeBomb();
        bomb = to.getWorld().spawn(to.getLocation().add(0, 2, 0), ItemDisplay.class, d -> {
            d.setItemStack(new ItemStack(Material.TNT));
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.FIXED);
            d.setTransformation(new Transformation(new Vector3f(0, 0.6f, 0), new AxisAngle4f(),
                    new Vector3f(0.6f, 0.6f, 0.6f), new AxisAngle4f()));
        });
        to.addPassenger(bomb);
        if (from != null) {
            for (Player p : players()) {
                p.sendMessage(ChatColor.GOLD + from.getName() + ChatColor.YELLOW + " gaf de bom aan " + ChatColor.GOLD + to.getName() + "!");
            }
        }
    }

    private void removeBomb() {
        if (bomb != null && bomb.isValid()) bomb.remove();
        bomb = null;
    }

    private void explode(Player victim) {
        Location at = victim.getLocation();
        victim.getInventory().clear();
        removeBomb();
        holder = null;
        at.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, at, 1);
        at.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 1f);
        alive.remove(victim.getUniqueId());
        victim.setVelocity(victim.getVelocity().setY(1.2));
        for (Player p : players()) {
            p.sendMessage(ChatColor.RED + "" + ChatColor.BOLD + "BOEM! " + ChatColor.GOLD + victim.getName()
                    + ChatColor.YELLOW + " ligt eruit. Nog " + alive.size() + " over.");
        }
        victim.showTitle(Title.title(LobbyGames.legacy("&c&lBOEM!"), LobbyGames.legacy("&7Je ligt eruit."),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500))));
        // Even laten vliegen, dan terug naar waar je vandaan kwam.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (victim.isOnline() && isIn(victim.getUniqueId())) leave(victim, true);
        }, 30L);
        if (!checkWinner()) Bukkit.getScheduler().runTaskLater(plugin, this::nextRound, 60L);
    }

    /** Nog één over? Die wint. @return true als het spel klaar is. */
    private boolean checkWinner() {
        if (state != State.PLAYING) return true;
        if (alive.size() > 1) return false;
        if (alive.isEmpty()) {
            stop(null);
            return true;
        }
        Player winner = Bukkit.getPlayer(alive.iterator().next());
        stop(winner);
        return true;
    }

    /** Spel afgelopen (winner mag null zijn); iedereen terug. */
    private void stop(Player winner) {
        if (task != null) task.cancel();
        task = null;
        removeBomb();
        holder = null;
        State was = state;
        state = State.IDLE;
        if (winner != null) {
            wins.merge(winner.getUniqueId(), 1, Integer::sum);
            names.put(winner.getUniqueId(), winner.getName());
            save();
            Bukkit.broadcast(LobbyGames.legacy("&6&lHot Potato: &f" + winner.getName() + " &eheeft gewonnen!"));
            winner.showTitle(Title.title(LobbyGames.legacy("&a&lGEWONNEN!"), LobbyGames.legacy("&7Jij bent de laatste!"),
                    Title.Times.times(Duration.ofMillis(200), Duration.ofSeconds(3), Duration.ofMillis(500))));
            Firework fw = winner.getWorld().spawn(winner.getLocation().add(0, 1, 0), Firework.class, f -> {
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.STAR).withColor(Color.ORANGE, Color.RED).build());
                meta.setPower(1);
                f.setFireworkMeta(meta);
            });
            fw.setMetadata("hotpotato", new org.bukkit.metadata.FixedMetadataValue(plugin, true));
        }
        alive.clear();
        List<UUID> everyone = new ArrayList<>(returnTo.keySet());
        long delay = winner != null ? 60L : 0L;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : everyone) {
                Player p = Bukkit.getPlayer(uuid);
                Location from = returnTo.remove(uuid);
                if (p == null) continue;
                cleanup(p);
                restore(p);
                if (from != null) teleport(p, from);
            }
        }, delay);
        if (was == State.WAITING && winner == null) {
            for (UUID uuid : everyone) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) p.sendMessage(ChatColor.GRAY + "Hot Potato gaat niet door.");
            }
        }
    }

    // ---------- Regels tijdens het spel ----------

    /** Slaan = de bom doorgeven; niemand krijgt echte schade. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework && event.getDamager().hasMetadata("hotpotato")) {
            event.setCancelled(true);
            return;
        }
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) return;
        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity();
        boolean attackerIn = alive.contains(attacker.getUniqueId());
        boolean victimIn = alive.contains(victim.getUniqueId());
        if (!attackerIn && !victimIn) return;
        event.setCancelled(true);
        if (state != State.PLAYING || !attackerIn || !victimIn || !attacker.getUniqueId().equals(holder)) return;
        long now = System.currentTimeMillis();
        if (now - lastPass < passCooldownMs) return;
        if (victim.getUniqueId().equals(lastHolder) && now - lastPass < passCooldownMs * 2) return; // niet meteen terug
        giveBomb(victim, attacker);
    }

    /** Ook geen andere schade (vallen, enz.) tijdens het spel. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && isIn(event.getEntity().getUniqueId())) event.setCancelled(true);
    }

    /** Niet van het eiland af: terug naar het midden. */
    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (state == State.IDLE || !isIn(event.getPlayer().getUniqueId())) return;
        if (pos1 == null || pos2 == null) {
            if (event.getTo().getY() < spawn.getY() - 15) teleport(event.getPlayer(), scatter());
            return;
        }
        Location to = event.getTo();
        int minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
        int maxX = Math.max(pos1.getBlockX(), pos2.getBlockX()) + 1;
        int minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
        int maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ()) + 1;
        boolean outside = to.getX() < minX || to.getX() > maxX || to.getZ() < minZ || to.getZ() > maxZ
                || to.getY() < Math.min(pos1.getY(), pos2.getY()) - 10;
        if (outside) teleport(event.getPlayer(), scatter());
    }

    /** Ergens anders heen (commando, NPC, ...) = stoppen met het spel. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player p = event.getPlayer();
        if (!isIn(p.getUniqueId()) || ownTeleport.contains(p.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (isIn(p.getUniqueId())) leave(p, false);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (!isIn(p.getUniqueId())) return;
        Location from = returnTo.get(p.getUniqueId());
        leave(p, false);
        if (from != null) p.teleport(from); // bij opnieuw inloggen niet op het eiland
    }

    /** Tijdens het spel alleen chatten, /hotpotato en /msg. */
    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (state != State.PLAYING || !alive.contains(event.getPlayer().getUniqueId())) return;
        String cmd = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (Arrays.asList("hotpotato", "hp", "msg", "tell", "w", "r").contains(cmd)) return;
        event.setCancelled(true);
        event.getPlayer().sendMessage(ChatColor.RED + "Tijdens Hot Potato kan dat niet. Stoppen: /hotpotato leave");
    }

    /** Geen spullen droppen of verschuiven tijdens het spel (de bom blijft waar hij is). */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (isIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(org.bukkit.event.inventory.InventoryClickEvent event) {
        if (isIn(event.getWhoClicked().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(org.bukkit.event.player.PlayerSwapHandItemsEvent event) {
        if (isIn(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    /** Server gecrasht tijdens het spel? Bij inloggen alles terug, en terug naar waar je was. */
    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) restoreIfSaved(p);
        }, 5L);
    }

    // ---------- Eigen spullen opbergen ----------

    private void store(Player p) {
        ConfigurationSection s = saves.createSection(p.getUniqueId().toString());
        s.set("items", encode(p.getInventory().getContents()));
        s.set("level", p.getLevel());
        s.set("exp", (double) p.getExp());
        LobbyGames.saveLocation(s, "location", p.getLocation());
        saveSaves();
        p.getInventory().clear();
    }

    private void restore(Player p) {
        ConfigurationSection s = saves.getConfigurationSection(p.getUniqueId().toString());
        if (s == null) return;
        p.getInventory().clear();
        p.setItemOnCursor(null);
        p.getInventory().setContents(decode(s.getStringList("items")));
        p.setLevel(s.getInt("level"));
        p.setExp((float) s.getDouble("exp"));
        saves.set(p.getUniqueId().toString(), null);
        saveSaves();
    }

    private void restoreIfSaved(Player p) {
        if (isIn(p.getUniqueId()) || !saves.contains(p.getUniqueId().toString())) return;
        Location back = LobbyGames.loadLocation(saves.getConfigurationSection(p.getUniqueId() + ".location"));
        cleanup(p);
        restore(p);
        if (back != null) teleport(p, back);
        p.sendMessage(ChatColor.YELLOW + "Je hebt je spullen van Hot Potato teruggekregen.");
    }

    private void saveSaves() {
        try {
            saves.save(savesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Kon hotpotato-saves.yml niet opslaan: " + e.getMessage());
        }
    }

    private static List<String> encode(ItemStack[] items) {
        List<String> list = new ArrayList<>();
        for (ItemStack item : items) {
            list.add(item == null || item.getType().isAir() ? "" : java.util.Base64.getEncoder().encodeToString(item.serializeAsBytes()));
        }
        return list;
    }

    private static ItemStack[] decode(List<String> list) {
        ItemStack[] items = new ItemStack[list.size()];
        for (int i = 0; i < list.size(); i++) {
            String s = list.get(i);
            if (s == null || s.isEmpty()) continue;
            try {
                items[i] = ItemStack.deserializeBytes(java.util.Base64.getDecoder().decode(s));
            } catch (RuntimeException ignored) {
                items[i] = null;
            }
        }
        return items;
    }

    // ---------- Hulpjes ----------

    private List<Player> players() {
        List<Player> list = new ArrayList<>();
        for (UUID uuid : returnTo.keySet()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) list.add(p);
        }
        return list;
    }

    /** Willekeurige plek rond het midden (binnen de grens als die er is). */
    private Location scatter() {
        Location loc = spawn.clone();
        loc.add(random.nextDouble() * 6 - 3, 0, random.nextDouble() * 6 - 3);
        loc.setYaw(random.nextFloat() * 360f);
        Location safe = loc.getWorld().getHighestBlockAt(loc).getLocation().add(0.5, 1, 0.5);
        if (Math.abs(safe.getY() - spawn.getY()) > 4) return spawn.clone();
        safe.setYaw(loc.getYaw());
        return safe;
    }

    private void teleport(Player p, Location to) {
        ownTeleport.add(p.getUniqueId());
        try {
            // Een passagier (de bom) blokkeert teleporteren: even eraf en daarna weer erop.
            List<Entity> riders = new ArrayList<>(p.getPassengers());
            for (Entity e : riders) p.removePassenger(e);
            p.teleport(to);
            for (Entity e : riders) if (e.isValid()) {
                e.teleport(to);
                p.addPassenger(e);
            }
        } finally {
            ownTeleport.remove(p.getUniqueId());
        }
    }
}
