package com.example.survivalextras;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Loot drop-event: ergens in de survival-wereld landt na een aftelling een kist met
 * decoratie-hoofden en handige spullen. Iedereen ziet de coördinaten, een balk bovenin
 * beeld en een lichtstraal in de lucht. Wie hem als eerste opent, wordt genoemd.
 *
 *  /lootdrop start [tijd]  - ergens willekeurig (binnen de wereldgrens)
 *  /lootdrop hier [tijd]   - op de plek waar je staat
 *  /lootdrop stop          - afbreken / kist weghalen
 *  /lootdrop info          - status
 *  /lootdrop plan <UU:MM> [aftellen] - eenmalig event: meteen aangekondigd (ook in Discord),
 *                          op het tijdstip komen de coördinaten en landt hij na het aftellen
 *  /lootdrop plan annuleer
 * Aankondigingen gaan via de DiscordBridge-plugin naar het aankondigingskanaal van de bot.
 */
final class LootDrop implements TabExecutor, Listener {

    private static final class Loot {
        Material material;
        int min;
        int max;
        int weight;
    }

    private static final ZoneId ZONE = ZoneId.of("Europe/Amsterdam");

    private final JavaPlugin plugin;
    private final Random random = new Random();
    private final int countdownSeconds;
    private final double borderFraction;
    private final int minSpawnDistance;
    private final int rolls;
    private final int heads;
    private final boolean playerHeads;
    private final List<String> decorationHeads;
    private final List<Loot> loot = new ArrayList<>();
    private final int removeAfterMinutes;

    // Gepland event (0 = niets gepland); bewaard in lootdrop.yml, zodat het een herstart overleeft
    private final File planFile;
    private final YamlConfiguration planData;
    private long plannedAt;
    private int plannedCountdown;
    private long lastReminder = -1;

    // Huidige drop (hooguit één tegelijk)
    private Location target;          // waar hij landt / ligt
    private BukkitTask task;
    private BossBar bar;
    private boolean landed;
    private boolean found;
    private long landedAt;

    LootDrop(JavaPlugin plugin, ConfigurationSection c) {
        this.plugin = plugin;
        countdownSeconds = c.getInt("countdown-seconds", 300);
        borderFraction = c.getDouble("border-fraction", 0.7);
        minSpawnDistance = c.getInt("min-distance-from-spawn", 300);
        rolls = c.getInt("rolls", 7);
        heads = c.getInt("heads", 3);
        playerHeads = c.getBoolean("player-heads", true);
        decorationHeads = c.getStringList("decoration-heads");
        removeAfterMinutes = c.getInt("remove-after-minutes", 30);
        planFile = new File(plugin.getDataFolder(), "lootdrop.yml");
        planData = YamlConfiguration.loadConfiguration(planFile);
        plannedAt = planData.getLong("planned-at", 0);
        plannedCountdown = planData.getInt("countdown", countdownSeconds);
        for (Map<?, ?> raw : c.getMapList("loot")) {
            Material m = Material.matchMaterial(String.valueOf(raw.get("item")));
            if (m == null) {
                plugin.getLogger().warning("loot-drop: onbekend item '" + raw.get("item") + "', overgeslagen.");
                continue;
            }
            Loot l = new Loot();
            l.material = m;
            String[] amount = String.valueOf(raw.containsKey("amount") ? raw.get("amount") : "1").split("-");
            l.min = Math.max(1, parse(amount[0], 1));
            l.max = Math.max(l.min, amount.length > 1 ? parse(amount[1], l.min) : l.min);
            l.weight = Math.max(1, parse(String.valueOf(raw.containsKey("weight") ? raw.get("weight") : "10"), 10));
            loot.add(l);
        }
        // Elke minuut: een gelande kist na een tijd opruimen. Elke seconde: gepland event?
        Bukkit.getScheduler().runTaskTimer(plugin, this::minuteCheck, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::planCheck, 100L, 20L);
    }

    private static int parse(String text, int def) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    String describe() {
        return loot.size() + " soorten loot, gepland: " + (plannedAt > 0 ? clock(plannedAt) : "niets");
    }

    // ---------- Commando ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("plan")) {
            plan(sender, args);
            return true;
        }
        int seconds = countdownSeconds;
        if (args.length >= 2) {
            Long parsed = parseTime(args[1]);
            if (parsed == null || parsed < 0 || parsed > 3600) {
                sender.sendMessage(ChatColor.RED + "Tijd zoals 30s, 5m of 10m (max 1 uur).");
                return true;
            }
            seconds = parsed.intValue();
        }
        switch (sub) {
            case "start":
                if (busy(sender)) return true;
                sender.sendMessage(ChatColor.GRAY + "Plek zoeken...");
                final int s = seconds;
                pickLocation(Bukkit.getWorlds().get(0), 15, loc -> {
                    if (loc == null) sender.sendMessage(ChatColor.RED + "Geen goede plek gevonden (alles water?). Probeer opnieuw.");
                    else start(loc, s);
                });
                return true;
            case "hier":
                if (!(sender instanceof Player)) return true;
                if (busy(sender)) return true;
                start(((Player) sender).getLocation().getBlock().getLocation(), seconds);
                return true;
            case "stop":
                if (target == null) {
                    sender.sendMessage(ChatColor.YELLOW + "Er is geen loot drop bezig.");
                    return true;
                }
                clear(true);
                Bukkit.broadcast(SurvivalExtras.legacy("&7De loot drop is afgelast."));
                discord(0x99AAB5, ":x: Loot drop afgelast", "De loot drop gaat niet door.");
                return true;
            default:
                if (target == null) {
                    sender.sendMessage(ChatColor.GRAY + "Geen loot drop bezig. Plannen: /lootdrop plan 20:00, "
                            + "meteen: /lootdrop start [tijd], testen: /lootdrop hier [tijd]");
                } else {
                    sender.sendMessage(ChatColor.GOLD + "Loot drop bij " + coords(target) + ": "
                            + (found ? "al gevonden" : landed ? "ligt klaar" : "onderweg"));
                }
                sender.sendMessage(ChatColor.GRAY + describe());
                return true;
        }
    }

    // ---------- Plannen ----------

    /** /lootdrop plan <UU:MM> [aftellen] of /lootdrop plan annuleer */
    private void plan(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /lootdrop plan <UU:MM> [aftellen]  of  /lootdrop plan annuleer");
            if (plannedAt > 0) sender.sendMessage(ChatColor.GRAY + "Nu gepland: " + clock(plannedAt));
            return;
        }
        if (args[1].equalsIgnoreCase("annuleer")) {
            if (plannedAt == 0) {
                sender.sendMessage(ChatColor.YELLOW + "Er is niets gepland.");
                return;
            }
            setPlan(0, countdownSeconds);
            Bukkit.broadcast(SurvivalExtras.legacy("&7Het loot drop-event gaat niet door."));
            discord(0x99AAB5, ":x: Loot drop-event afgelast", "Het geplande loot drop-event gaat niet door.");
            return;
        }
        LocalTime time;
        try {
            time = LocalTime.parse(args[1].length() == 4 ? "0" + args[1] : args[1]);
        } catch (DateTimeParseException e) {
            sender.sendMessage(ChatColor.RED + "Tijd als UU:MM, bv. 20:00.");
            return;
        }
        int countdown = countdownSeconds;
        if (args.length >= 3) {
            Long parsed = parseTime(args[2]);
            if (parsed == null || parsed < 0 || parsed > 3600) {
                sender.sendMessage(ChatColor.RED + "Aftellen zoals 2m of 5m (max 1 uur).");
                return;
            }
            countdown = parsed.intValue();
        }
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime when = now.toLocalDate().atTime(time).atZone(ZONE);
        if (!when.isAfter(now)) when = when.plusDays(1);
        setPlan(when.toInstant().toEpochMilli(), countdown);

        String day = when.toLocalDate().equals(now.toLocalDate()) ? "Vandaag" : "Morgen";
        String at = time.toString();
        Bukkit.broadcast(SurvivalExtras.legacy("&6&lLOOT DROP-EVENT! &e" + day + " om &f" + at
                + "&e valt er ergens een kist met loot. Dan komen de coördinaten in de chat!"));
        discord(0xF1C40F, ":package: Loot drop-event: " + day.toLowerCase(Locale.ROOT) + " om " + at + "!",
                day + " om **" + at + "** valt er een **loot drop** op OoitGedacht SMP!\n\n"
                        + "Om " + at + " worden de coördinaten bekendgemaakt, in de game én hier. Daarna heb je **"
                        + time(countdown) + "** om erheen te gaan voordat de kist landt.\n"
                        + "In de kist: decoratie-hoofden en handige spullen. Wie is er het eerst bij?");
        sender.sendMessage(ChatColor.GREEN + "Gepland: " + clock(plannedAt) + " (aftellen " + time(countdown) + "). "
                + "Afzeggen: /lootdrop plan annuleer");
        plugin.getLogger().info("[Loot drop] Gepland op " + clock(plannedAt) + " door " + sender.getName() + ".");
    }

    private void setPlan(long at, int countdown) {
        plannedAt = at;
        plannedCountdown = countdown;
        lastReminder = -1;
        planData.set("planned-at", at == 0 ? null : at);
        planData.set("countdown", countdown);
        try {
            planData.save(planFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Kon lootdrop.yml niet opslaan: " + e.getMessage());
        }
    }

    /** Elke seconde: herinneringen in de game, en op het tijdstip de drop starten. */
    private void planCheck() {
        if (plannedAt == 0) return;
        long now = System.currentTimeMillis();
        long left = plannedAt - now;
        if (left <= 0) {
            long late = -left;
            int countdown = plannedCountdown;
            setPlan(0, countdownSeconds);
            if (late > 2 * 3600_000L) {
                // Server sliep op het moment zelf en is pas veel later wakker: niet meer doen.
                plugin.getLogger().info("[Loot drop] Gepland event gemist (server sliep), overgeslagen.");
                return;
            }
            if (target != null) return; // er loopt al een drop
            pickLocation(Bukkit.getWorlds().get(0), 15, loc -> {
                if (loc != null && target == null) start(loc, countdown);
                else if (loc == null) plugin.getLogger().warning("[Loot drop] Geen plek gevonden voor het geplande event.");
            });
            return;
        }
        long minutes = (left + 59_999) / 60_000;
        if ((minutes == 30 || minutes == 10 || minutes == 5 || minutes == 1) && minutes != lastReminder) {
            lastReminder = minutes;
            Bukkit.broadcast(SurvivalExtras.legacy("&6Loot drop-event over &f" + minutes + (minutes == 1 ? " minuut" : " minuten")
                    + "&6! Dan komen de coördinaten in de chat."));
        }
    }

    private static String clock(long millis) {
        ZonedDateTime t = Instant.ofEpochMilli(millis).atZone(ZONE);
        boolean today = t.toLocalDate().equals(ZonedDateTime.now(ZONE).toLocalDate());
        return (today ? "vandaag " : t.toLocalDate() + " ") + String.format(Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
    }

    /** Naar Discord via de DiscordBridge-plugin (als die er is). */
    private void discord(int color, String title, String text) {
        if (!Bukkit.getPluginManager().isPluginEnabled("DiscordBridge")) return;
        Base64.Encoder b64 = Base64.getEncoder();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discordbridge announce " + Integer.toHexString(color) + " "
                + b64.encodeToString(title.getBytes(StandardCharsets.UTF_8)) + " "
                + b64.encodeToString(text.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean busy(CommandSender sender) {
        if (target == null) return false;
        sender.sendMessage(ChatColor.YELLOW + "Er is al een loot drop bezig. Afbreken: /lootdrop stop");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean plan = args.length >= 2 && args[0].equalsIgnoreCase("plan");
        List<String> options = args.length == 1 ? Arrays.asList("plan", "start", "hier", "stop", "info")
                : plan && args.length == 2 ? Arrays.asList("20:00", "21:00", "annuleer")
                : plan && args.length == 3 ? Arrays.asList("2m", "5m", "10m")
                : args.length == 2 ? Arrays.asList("0s", "30s", "1m", "5m", "10m") : Collections.emptyList();
        List<String> result = new ArrayList<>();
        for (String o : options) if (o.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) result.add(o);
        return result;
    }

    // ---------- Verloop ----------

    private void start(Location where, int seconds) {
        target = where.clone().add(0.5, 0, 0.5);
        landed = false;
        found = false;
        discord(0xE67E22, ":package: Loot drop onderweg!", "De kist landt over **" + time(seconds) + "** bij **"
                + coords(target) + "** (survival-wereld). Snel erheen!");
        plugin.getLogger().info("[Loot drop] Landt over " + seconds + " s bij " + coords(target) + ".");

        bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        Bukkit.broadcast(SurvivalExtras.legacy("&6&lLOOT DROP! &eOver &f" + time(seconds) + " &elandt er een kist bij &f"
                + coords(target) + "&e. Wie is er het eerst?"));
        Title title = Title.title(SurvivalExtras.legacy("&6&lLOOT DROP"), SurvivalExtras.legacy("&e" + coords(target)),
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(4), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1f);
        }

        final int[] left = {seconds};
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!landed) {
                if (left[0] <= 0) {
                    land();
                } else {
                    bar.name(SurvivalExtras.legacy("&6Loot drop &f" + coords(target) + " &7- landt over &f" + time(left[0])));
                    bar.progress(Math.max(0f, Math.min(1f, left[0] / (float) Math.max(1, seconds))));
                    if (left[0] == 60 || left[0] == 10) {
                        Bukkit.broadcast(SurvivalExtras.legacy("&eDe loot drop landt over &f" + time(left[0]) + " &ebij &f" + coords(target) + "&e!"));
                    }
                    left[0]--;
                }
            }
            if (!found) {
                for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
                beam();
            }
        }, 0L, 20L);
    }

    /** Lichtstraal boven de plek, van ver te zien. */
    private void beam() {
        World world = target.getWorld();
        for (int y = 0; y < 60; y += 2) {
            world.spawnParticle(Particle.END_ROD, target.getX(), target.getY() + y, target.getZ(),
                    1, 0.05, 0.3, 0.05, 0, null, true);
        }
    }

    private void land() {
        landed = true;
        landedAt = System.currentTimeMillis();
        World world = target.getWorld();
        world.getChunkAtAsync(target).thenAccept(chunk -> {
            if (target == null) return; // intussen afgelast
            Block block = target.getBlock();
            block.setType(Material.CHEST);
            if (block.getState() instanceof Chest) fill(((Chest) block.getState()).getBlockInventory());
            world.strikeLightningEffect(target);
            Firework fw = world.spawn(target.clone().add(0, 1, 0), Firework.class, f -> {
                FireworkMeta meta = f.getFireworkMeta();
                meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                        .withColor(Color.ORANGE, Color.YELLOW).withFade(Color.WHITE).flicker(true).build());
                f.setFireworkMeta(meta);
            });
            fw.detonate();
            bar.name(SurvivalExtras.legacy("&6Loot drop ligt klaar bij &f" + coords(target) + "&6!"));
            bar.progress(0f);
            Bukkit.broadcast(SurvivalExtras.legacy("&6&l✦ &eDe loot drop is geland bij &f" + coords(target) + "&e!"));
            plugin.getLogger().info("[Loot drop] Geland bij " + coords(target) + ".");
        });
    }

    private void fill(Inventory inv) {
        List<ItemStack> items = new ArrayList<>();
        int totalWeight = loot.stream().mapToInt(l -> l.weight).sum();
        for (int i = 0; i < rolls && totalWeight > 0; i++) {
            int pick = random.nextInt(totalWeight);
            for (Loot l : loot) {
                pick -= l.weight;
                if (pick < 0) {
                    int amount = l.min + random.nextInt(l.max - l.min + 1);
                    items.add(new ItemStack(l.material, Math.min(amount, l.material.getMaxStackSize())));
                    break;
                }
            }
        }
        List<String> names = new ArrayList<>(decorationHeads);
        if (playerHeads) for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        Collections.shuffle(names, random);
        for (int i = 0; i < heads && i < names.size(); i++) items.add(head(names.get(i)));

        List<Integer> slots = new ArrayList<>();
        for (int i = 0; i < inv.getSize(); i++) slots.add(i);
        Collections.shuffle(slots, random);
        for (int i = 0; i < items.size() && i < slots.size(); i++) inv.setItem(slots.get(i), items.get(i));
    }

    /** Hoofd met de skin van dit account (MHF_... = bekende decoratie-hoofden van Mojang-accounts). */
    private static ItemStack head(String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(Bukkit.createProfile(name));
        boolean decoration = name.startsWith("MHF_");
        meta.displayName(SurvivalExtras.legacy(decoration ? "&e" + headName(name) : "&eHoofd van " + name)
                .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        head.setItemMeta(meta);
        return head;
    }

    private static String headName(String mhf) {
        switch (mhf) {
            case "MHF_Present1": case "MHF_Present2": return "Cadeautje";
            case "MHF_Chest": return "Kistje";
            case "MHF_Cake": return "Taart";
            case "MHF_Melon": return "Meloen";
            case "MHF_Pumpkin": return "Pompoen";
            case "MHF_TNT": return "TNT";
            case "MHF_Cactus": return "Cactus";
            case "MHF_OakLog": return "Boomstam";
            case "MHF_Question": return "Vraagteken";
            case "MHF_Exclamation": return "Uitroepteken";
            default: return "Decoratiehoofd";
        }
    }

    /** Eerste keer open = gevonden. */
    @EventHandler(ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!landed || found || !isDropChest(event.getInventory().getLocation())) return;
        found = true;
        for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        Bukkit.broadcast(SurvivalExtras.legacy("&6&l✦ &f" + event.getPlayer().getName() + " &eheeft de loot drop gevonden!"));
        discord(0x2ECC71, ":tada: Loot drop gevonden!", "**" + event.getPlayer().getName() + "** was er als eerste bij!");
        plugin.getLogger().info("[Loot drop] Gevonden door " + event.getPlayer().getName() + ".");
    }

    /** Leeg? Dan verdwijnt de kist. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!landed || !isDropChest(event.getInventory().getLocation())) return;
        if (event.getInventory().isEmpty()) {
            Bukkit.getScheduler().runTask(plugin, () -> clear(true));
        }
    }

    /** Kist kapot slaan mag (dan valt alles eruit), maar dan is het event ook klaar. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (landed && isDropChest(event.getBlock().getLocation())) {
            if (!found) {
                Bukkit.broadcast(SurvivalExtras.legacy("&6&l✦ &f" + event.getPlayer().getName() + " &eheeft de loot drop gevonden!"));
                discord(0x2ECC71, ":tada: Loot drop gevonden!", "**" + event.getPlayer().getName() + "** was er als eerste bij!");
            }
            clear(false);
        }
    }

    /** Vuurwerk van de landing doet geen schade. */
    @EventHandler(ignoreCancelled = true)
    public void onFirework(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework && target != null
                && event.getDamager().getLocation().distanceSquared(target) < 100) {
            event.setCancelled(true);
        }
    }

    private boolean isDropChest(Location loc) {
        return target != null && loc != null && loc.getWorld() == target.getWorld()
                && loc.getBlockX() == target.getBlockX() && loc.getBlockY() == target.getBlockY()
                && loc.getBlockZ() == target.getBlockZ();
    }

    /** Alles opruimen; removeChest = de kist (met wat er nog in zit) weghalen. */
    private void clear(boolean removeChest) {
        if (task != null) task.cancel();
        task = null;
        if (bar != null) for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
        if (removeChest && landed && target != null && target.getBlock().getType() == Material.CHEST) {
            if (target.getBlock().getState() instanceof Chest) ((Chest) target.getBlock().getState()).getBlockInventory().clear();
            target.getBlock().setType(Material.AIR);
            target.getWorld().spawnParticle(Particle.CLOUD, target.clone().add(0, 0.5, 0), 20, 0.3, 0.3, 0.3, 0.02);
        }
        target = null;
        landed = false;
        found = false;
    }

    private void minuteCheck() {
        long now = System.currentTimeMillis();
        if (target != null && landed && now - landedAt > removeAfterMinutes * 60_000L) {
            Bukkit.broadcast(SurvivalExtras.legacy("&7De loot drop bij " + coords(target) + " is verdwenen."));
            if (!found) discord(0x99AAB5, ":hourglass: Loot drop verdwenen", "Niemand heeft de loot drop op tijd gevonden.");
            clear(true);
        }
    }

    // ---------- Plek zoeken ----------

    /** Willekeurige plek op land, binnen de wereldgrens en niet te dicht bij spawn. */
    private void pickLocation(World world, int tries, Consumer<Location> done) {
        if (tries <= 0) {
            done.accept(null);
            return;
        }
        WorldBorder border = world.getWorldBorder();
        double half = border.getSize() / 2 * borderFraction;
        int x = (int) (border.getCenter().getX() + (random.nextDouble() * 2 - 1) * half);
        int z = (int) (border.getCenter().getZ() + (random.nextDouble() * 2 - 1) * half);
        Location spawn = world.getSpawnLocation();
        if (Math.hypot(x - spawn.getX(), z - spawn.getZ()) < minSpawnDistance) {
            pickLocation(world, tries - 1, done);
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            Block top = world.getHighestBlockAt(x, z);
            Material type = top.getType();
            if (top.isLiquid() || type == Material.ICE || type.name().endsWith("_LEAVES")) {
                pickLocation(world, tries - 1, done);
            } else {
                done.accept(top.getLocation().add(0, 1, 0));
            }
        });
    }

    // ---------- Hulpjes ----------

    private static String coords(Location loc) {
        return "X " + loc.getBlockX() + ", Y " + loc.getBlockY() + ", Z " + loc.getBlockZ();
    }

    private static String time(int seconds) {
        return seconds >= 60 ? (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60) : seconds + " s";
    }

    /** "30s", "5m", "1h"; zonder letter = seconden. */
    private static Long parseTime(String text) {
        String t = text.toLowerCase(Locale.ROOT).trim();
        try {
            if (t.endsWith("h")) return Long.parseLong(t.substring(0, t.length() - 1)) * 3600;
            if (t.endsWith("m")) return Long.parseLong(t.substring(0, t.length() - 1)) * 60;
            if (t.endsWith("s")) return Long.parseLong(t.substring(0, t.length() - 1));
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
