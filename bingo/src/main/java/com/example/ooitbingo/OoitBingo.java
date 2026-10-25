package com.example.ooitbingo;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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

/**
 * Bingo in survival. Beheer: /bingo start [line|full], /bingo stop.
 * Spelers: /bingo join (kaart in je hand), /bingo kaart, /bingo leave, /bingo top.
 * Een blok telt zodra je het in je inventory hebt; eerste met een rij (of volle kaart) wint XP.
 * Het spel staat in bingo.yml, dus het overleeft een herstart.
 */
public class OoitBingo extends JavaPlugin implements TabExecutor, Listener {

    static final int SIZE = 3;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Random random = new Random();
    private NamespacedKey mapKey;
    private File file;
    private YamlConfiguration data;

    // Het huidige spel
    boolean active;
    long gameId;                                   // = starttijd, staat ook op de kaarten
    String winMode = "line";
    final List<Material> card = new ArrayList<>();
    final Map<UUID, Set<Integer>> found = new LinkedHashMap<>();
    final Map<UUID, String> names = new HashMap<>();
    String winner;
    int mapId = -1;
    int version;                                    // telt op bij elke wijziging; de kaart tekent dan opnieuw

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mapKey = new NamespacedKey(this, "bingo_game");
        file = new File(getDataFolder(), "bingo.yml");
        data = YamlConfiguration.loadConfiguration(file);
        load();
        getCommand("bingo").setExecutor(this);
        getCommand("bingo").setTabCompleter(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> check(), 40L, 40L);
        if (mapId >= 0) attachRenderer(Bukkit.getMap(mapId));
        getLogger().info("OoitBingo geladen. " + (active ? "Bingo bezig met " + found.size() + " speler(s)." : "Geen bingo bezig."));
    }

    // ---------- Opslag ----------

    private void load() {
        active = data.getBoolean("active", false);
        gameId = data.getLong("game-id", 0);
        winMode = data.getString("win", "line");
        winner = data.getString("winner");
        mapId = data.getInt("map-id", -1);
        card.clear();
        for (String s : data.getStringList("card")) {
            Material m = Material.matchMaterial(s);
            card.add(m == null ? Material.DIRT : m);
        }
        found.clear();
        if (data.getConfigurationSection("players") != null) {
            for (String key : data.getConfigurationSection("players").getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    found.put(uuid, new HashSet<>(data.getIntegerList("players." + key + ".found")));
                    names.put(uuid, data.getString("players." + key + ".name", "?"));
                } catch (IllegalArgumentException ignored) {
                    // overslaan
                }
            }
        }
    }

    private void save() {
        data.set("active", active);
        data.set("game-id", gameId);
        data.set("win", winMode);
        data.set("winner", winner);
        data.set("map-id", mapId);
        List<String> list = new ArrayList<>();
        for (Material m : card) list.add(m.name());
        data.set("card", list);
        data.set("players", null);
        for (Map.Entry<UUID, Set<Integer>> e : found.entrySet()) {
            data.set("players." + e.getKey() + ".name", names.get(e.getKey()));
            data.set("players." + e.getKey() + ".found", new ArrayList<>(e.getValue()));
        }
        try {
            data.save(file);
        } catch (IOException e) {
            getLogger().severe("Kon bingo.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player ? (Player) sender : null;
        switch (sub) {
            case "join":
            case "meedoen":
                if (p != null) join(p);
                return true;
            case "kaart":
            case "card":
                if (p != null) {
                    if (!found.containsKey(p.getUniqueId()) || !active) p.sendMessage(ChatColor.RED + "Je doet niet mee aan een bingo. /bingo join");
                    else {
                        if (!hasCard(p)) giveCard(p);
                        openGui(p, p);
                    }
                }
                return true;
            case "leave":
                if (p != null) leave(p);
                return true;
            case "top":
                top(sender);
                return true;
            case "start":
                if (!admin(sender)) return true;
                start(sender, args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : getConfig().getString("win", "line"));
                return true;
            case "stop":
                if (!admin(sender)) return true;
                if (!active) sender.sendMessage(ChatColor.YELLOW + "Er is geen bingo bezig.");
                else end(null);
                return true;
            default:
                sender.sendMessage(ChatColor.GOLD + "=== Bingo ===");
                sender.sendMessage(ChatColor.YELLOW + "/bingo join" + ChatColor.GRAY + " - meedoen (je krijgt een bingokaart)");
                sender.sendMessage(ChatColor.YELLOW + "/bingo kaart" + ChatColor.GRAY + " - je kaart bekijken (of rechtsklik met de kaart)");
                sender.sendMessage(ChatColor.YELLOW + "/bingo top" + ChatColor.GRAY + " - wie heeft er het meest");
                sender.sendMessage(ChatColor.YELLOW + "/bingo leave" + ChatColor.GRAY + " - stoppen");
                if (sender.hasPermission("bingo.admin")) {
                    sender.sendMessage(ChatColor.GOLD + "Beheer: " + ChatColor.YELLOW + "/bingo start [line|full]" + ChatColor.GRAY + ", "
                            + ChatColor.YELLOW + "/bingo stop");
                }
                sender.sendMessage(ChatColor.GRAY + (active ? "Er is een bingo bezig (" + found.size() + " spelers)." : "Er is nu geen bingo bezig."));
                return true;
        }
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("bingo.admin")) return true;
        sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            options.addAll(Arrays.asList("join", "kaart", "top", "leave"));
            if (sender.hasPermission("bingo.admin")) options.addAll(Arrays.asList("start", "stop"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
            options.addAll(Arrays.asList("line", "full"));
        }
        List<String> result = new ArrayList<>();
        for (String o : options) if (o.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) result.add(o);
        return result;
    }

    // ---------- Spel ----------

    private void start(CommandSender sender, String mode) {
        if (active) {
            sender.sendMessage(ChatColor.YELLOW + "Er is al een bingo bezig. Stoppen: /bingo stop");
            return;
        }
        List<Material> newCard = makeCard();
        if (newCard.size() < SIZE * SIZE) {
            sender.sendMessage(ChatColor.RED + "Niet genoeg blokken in de config voor een volle kaart.");
            return;
        }
        card.clear();
        card.addAll(newCard);
        found.clear();
        names.clear();
        winner = null;
        winMode = mode.equals("full") ? "full" : "line";
        gameId = System.currentTimeMillis();
        active = true;
        MapView view = Bukkit.createMap(Bukkit.getWorlds().get(0));
        mapId = view.getId();
        attachRenderer(view);
        version++;
        save();

        String goal = winMode.equals("full") ? "de hele kaart" : "een rij, kolom of diagonaal";
        Bukkit.broadcast(LEGACY.deserialize("&6&lBINGO! &eEr is een bingo gestart. Doe mee met &f/bingo join&e!"));
        Bukkit.broadcast(LEGACY.deserialize("&7Verzamel de blokken op je kaart. Wie als eerste " + goal
                + " vol heeft, wint &f" + getConfig().getInt("reward-levels", 30) + " XP-levels&7!"));
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
        discord(0xF1C40F, ":game_die: Bingo gestart!", "Er is een **bingo** gestart op OoitGedacht SMP!\n"
                + "Doe mee in de game met `/bingo join`. Verzamel de 9 blokken op je kaart: wie als eerste "
                + goal + " vol heeft, wint **" + getConfig().getInt("reward-levels", 30) + " XP-levels**.");
        getLogger().info("[Bingo] Gestart door " + sender.getName() + " (" + winMode + ").");
    }

    /** Willekeurige kaart: easy/medium/hard volgens de config, alleen wat al open is. */
    private List<Material> makeCard() {
        FileConfiguration c = getConfig();
        boolean[] open = timeline();
        List<Material> result = new ArrayList<>();
        for (String tier : new String[]{"easy", "medium", "hard"}) {
            List<Material> pool = new ArrayList<>();
            for (String entry : c.getStringList("blocks." + tier)) {
                String[] parts = entry.split(":");
                if (parts.length > 1 && parts[1].equalsIgnoreCase("nether") && !open[0]) continue;
                if (parts.length > 1 && parts[1].equalsIgnoreCase("end") && !open[1]) continue;
                Material m = Material.matchMaterial(parts[0].trim());
                if (m == null || !m.isItem()) {
                    getLogger().warning("Onbekend blok in de config: " + parts[0]);
                    continue;
                }
                if (!pool.contains(m) && !result.contains(m)) pool.add(m);
            }
            Collections.shuffle(pool, random);
            int want = c.getInt("card." + tier, tier.equals("easy") ? 4 : tier.equals("medium") ? 3 : 2);
            for (int i = 0; i < want && i < pool.size(); i++) result.add(pool.get(i));
        }
        Collections.shuffle(result, random);
        return result.size() > SIZE * SIZE ? new ArrayList<>(result.subList(0, SIZE * SIZE)) : result;
    }

    /** {nether open, end open} volgens SurvivalTimeline; geen tijdlijn = alles open. */
    private boolean[] timeline() {
        File f = new File(getDataFolder().getParentFile(), "SurvivalTimeline/data.yml");
        if (!f.isFile()) return new boolean[]{true, true};
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        boolean over = y.getBoolean("season-ended", false);
        return new boolean[]{over || y.getBoolean("nether-unlocked", false), over || y.getBoolean("end-unlocked", false)};
    }

    private void join(Player p) {
        if (!active) {
            p.sendMessage(ChatColor.RED + "Er is nu geen bingo bezig. Hou de chat in de gaten!");
            return;
        }
        if (found.containsKey(p.getUniqueId())) {
            if (!hasCard(p)) giveCard(p);
            p.sendMessage(ChatColor.YELLOW + "Je doet al mee. Rechtsklik met je kaart om hem te bekijken.");
            return;
        }
        found.put(p.getUniqueId(), new HashSet<>());
        names.put(p.getUniqueId(), p.getName());
        save();
        giveCard(p);
        p.sendMessage(ChatColor.GOLD + "Je doet mee met de bingo!" + ChatColor.GRAY
                + " Verzamel de blokken op je kaart. Rechtsklik met de kaart voor de namen.");
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(p) && found.containsKey(other.getUniqueId())) {
                other.sendMessage(ChatColor.GRAY + p.getName() + " doet ook mee met de bingo.");
            }
        }
        check(p); // misschien heeft hij al blokken
    }

    private void leave(Player p) {
        if (found.remove(p.getUniqueId()) == null) {
            p.sendMessage(ChatColor.RED + "Je doet niet mee.");
            return;
        }
        names.remove(p.getUniqueId());
        removeCards(p);
        save();
        p.sendMessage(ChatColor.GRAY + "Je doet niet meer mee met de bingo.");
    }

    private void top(CommandSender sender) {
        if (!active || found.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Er is geen bingo bezig, of nog niemand doet mee.");
            return;
        }
        List<Map.Entry<UUID, Set<Integer>>> list = new ArrayList<>(found.entrySet());
        list.sort((a, b) -> b.getValue().size() - a.getValue().size());
        sender.sendMessage(ChatColor.GOLD + "=== Bingo: wie heeft het meest ===");
        for (int i = 0; i < Math.min(10, list.size()); i++) {
            sender.sendMessage(ChatColor.YELLOW + "" + (i + 1) + ". " + ChatColor.WHITE + names.get(list.get(i).getKey())
                    + ChatColor.GRAY + " - " + list.get(i).getValue().size() + "/" + (SIZE * SIZE));
        }
    }

    /** Elke 2 seconden: wie heeft er nieuwe blokken in zijn inventory? */
    private void check() {
        if (!active) return;
        for (Player p : Bukkit.getOnlinePlayers()) if (found.containsKey(p.getUniqueId())) check(p);
    }

    private void check(Player p) {
        Set<Integer> mine = found.get(p.getUniqueId());
        if (mine == null || !active) return;
        boolean changed = false;
        for (int i = 0; i < card.size(); i++) {
            if (mine.contains(i) || !p.getInventory().contains(card.get(i))) continue;
            mine.add(i);
            changed = true;
            p.sendMessage(ChatColor.GREEN + "✔ Bingo: " + ChatColor.WHITE + niceName(card.get(i))
                    + ChatColor.GRAY + " gevonden (" + mine.size() + "/" + (SIZE * SIZE) + ")");
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        }
        if (!changed) return;
        version++;
        save();
        if (hasWon(mine)) win(p);
    }

    private boolean hasWon(Set<Integer> mine) {
        if (winMode.equals("full")) return mine.size() >= SIZE * SIZE;
        for (int r = 0; r < SIZE; r++) {
            boolean row = true;
            boolean col = true;
            for (int c = 0; c < SIZE; c++) {
                row &= mine.contains(r * SIZE + c);
                col &= mine.contains(c * SIZE + r);
            }
            if (row || col) return true;
        }
        boolean d1 = true;
        boolean d2 = true;
        for (int i = 0; i < SIZE; i++) {
            d1 &= mine.contains(i * SIZE + i);
            d2 &= mine.contains(i * SIZE + (SIZE - 1 - i));
        }
        return d1 || d2;
    }

    private void win(Player p) {
        int levels = getConfig().getInt("reward-levels", 30);
        p.giveExpLevels(levels);
        Firework fw = p.getWorld().spawn(p.getLocation().add(0, 1, 0), Firework.class, f -> {
            FireworkMeta meta = f.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE).withColor(Color.YELLOW, Color.ORANGE).flicker(true).build());
            meta.setPower(1);
            f.setFireworkMeta(meta);
        });
        fw.getPersistentDataContainer().set(mapKey, PersistentDataType.LONG, gameId);
        p.showTitle(Title.title(LEGACY.deserialize("&6&lBINGO!"), LEGACY.deserialize("&eJij wint " + levels + " XP-levels!"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(4), Duration.ofSeconds(1))));
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        end(p);
    }

    /** Spel klaar (winner mag null zijn = gestopt). */
    private void end(Player winnerPlayer) {
        active = false;
        winner = winnerPlayer == null ? null : winnerPlayer.getName();
        version++;
        if (winnerPlayer != null) {
            Bukkit.broadcast(LEGACY.deserialize("&6&lBINGO! &f" + winner + " &eheeft gewonnen!"));
            discord(0x2ECC71, ":trophy: Bingo gewonnen!", "**" + winner + "** heeft de bingo gewonnen en krijgt **"
                    + getConfig().getInt("reward-levels", 30) + " XP-levels**!");
        } else {
            Bukkit.broadcast(LEGACY.deserialize("&7De bingo is gestopt."));
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (found.containsKey(p.getUniqueId()) && (winnerPlayer == null || !p.equals(winnerPlayer))) {
                p.showTitle(Title.title(LEGACY.deserialize(winnerPlayer == null ? "&7Bingo gestopt" : "&6BINGO!"),
                        LEGACY.deserialize(winnerPlayer == null ? "" : "&e" + winner + " heeft gewonnen"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofSeconds(1))));
            }
            removeCards(p);
        }
        save();
        getLogger().info("[Bingo] Afgelopen. Winnaar: " + (winner == null ? "geen" : winner) + ".");
    }

    // ---------- De kaart (map) ----------

    private void attachRenderer(MapView view) {
        if (view == null) return;
        for (MapRenderer r : new ArrayList<>(view.getRenderers())) view.removeRenderer(r);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.addRenderer(new BingoRenderer(this));
    }

    private ItemStack cardItem() {
        ItemStack item = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) item.getItemMeta();
        MapView view = mapId >= 0 ? Bukkit.getMap(mapId) : null;
        if (view != null) meta.setMapView(view);
        meta.displayName(LEGACY.deserialize("&6&lBingokaart").decoration(TextDecoration.ITALIC, false));
        meta.lore(Arrays.asList(
                LEGACY.deserialize("&7Verzamel de blokken op deze kaart.").decoration(TextDecoration.ITALIC, false),
                LEGACY.deserialize("&eRechtsklik &7voor de namen.").decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(mapKey, PersistentDataType.LONG, gameId);
        item.setItemMeta(meta);
        return item;
    }

    private void giveCard(Player p) {
        ItemStack card = cardItem();
        if (p.getInventory().getItemInMainHand().getType().isAir()) p.getInventory().setItemInMainHand(card);
        else p.getInventory().addItem(card).values().forEach(left -> p.getWorld().dropItem(p.getLocation(), left));
    }

    private boolean isCard(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(mapKey, PersistentDataType.LONG);
    }

    private boolean hasCard(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isCard(item) && item.getItemMeta().getPersistentDataContainer().get(mapKey, PersistentDataType.LONG) == gameId) {
                return true;
            }
        }
        return false;
    }

    private void removeCards(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) if (isCard(contents[i])) p.getInventory().setItem(i, null);
    }

    /** Oude kaarten (van een afgelopen bingo) opruimen bij inloggen. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        for (ItemStack item : p.getInventory().getContents()) {
            if (isCard(item) && (!active || item.getItemMeta().getPersistentDataContainer().get(mapKey, PersistentDataType.LONG) != gameId)) {
                removeCards(p);
                break;
            }
        }
        if (active && found.containsKey(p.getUniqueId())) names.put(p.getUniqueId(), p.getName());
    }

    /** Rechtsklik met de kaart = overzicht met de echte blokken. */
    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isCard(event.getItem())) return;
        event.setCancelled(true);
        Player p = event.getPlayer();
        if (!active) {
            removeCards(p);
            return;
        }
        openGui(p, p);
    }

    // ---------- Overzicht (inventory) ----------

    private static final class Gui implements InventoryHolder {
        Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private void openGui(Player viewer, Player of) {
        Set<Integer> mine = found.getOrDefault(of.getUniqueId(), Collections.emptySet());
        Gui holder = new Gui();
        Inventory inv = Bukkit.createInventory(holder, 27, LEGACY.deserialize("&6&lBingo &8- &7" + mine.size() + "/" + (SIZE * SIZE)
                + (winMode.equals("full") ? " (hele kaart)" : " (een rij)")));
        holder.inventory = inv;
        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fm = filler.getItemMeta();
        fm.displayName(Component.text(" "));
        filler.setItemMeta(fm);
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);
        for (int i = 0; i < card.size(); i++) {
            int slot = (i / SIZE) * 9 + 3 + (i % SIZE);
            boolean got = mine.contains(i);
            ItemStack item = new ItemStack(card.get(i));
            ItemMeta meta = item.getItemMeta();
            meta.displayName(LEGACY.deserialize((got ? "&a✔ " : "&c✘ ") + "&f" + niceName(card.get(i)))
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(Collections.singletonList(LEGACY.deserialize(got ? "&aGevonden!" : "&7Nog niet gevonden")
                    .decoration(TextDecoration.ITALIC, false)));
            if (got) meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
            inv.setItem(slot, item);
        }
        viewer.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Gui) event.setCancelled(true);
    }

    /** Vuurwerk van de winnaar doet geen schade. */
    @EventHandler
    public void onFirework(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework
                && event.getDamager().getPersistentDataContainer().has(mapKey, PersistentDataType.LONG)) {
            event.setCancelled(true);
        }
    }

    // ---------- Hulpjes ----------

    static String niceName(Material m) {
        String name = m.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    private void discord(int color, String title, String text) {
        if (!getConfig().getBoolean("discord", true) || !Bukkit.getPluginManager().isPluginEnabled("DiscordBridge")) return;
        Base64.Encoder b64 = Base64.getEncoder();
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "discordbridge announce " + Integer.toHexString(color) + " "
                + b64.encodeToString(title.getBytes(StandardCharsets.UTF_8)) + " "
                + b64.encodeToString(text.getBytes(StandardCharsets.UTF_8)));
    }
}
