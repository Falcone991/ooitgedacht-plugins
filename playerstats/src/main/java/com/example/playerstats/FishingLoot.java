package com.example.playerstats;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.generator.structure.StructureType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCursor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Bonusvangst bij het vissen, beter naarmate je vis-level hoger is: met een kans die per
 * level oploopt vis je naast de gewone vangst iets extra's op. Hogere levels openen
 * betere spullen (schatkaarten, diamanten, ...). Alles staat in config.yml (fishing-loot).
 */
final class FishingLoot {

    private static final class Entry {
        String material;
        int minLevel;
        int weight;
        int minAmount;
        int maxAmount;
        int enchantLevels;
        boolean announce;
        String name;
    }

    private final Logger logger;
    private final boolean enabled;
    private final double baseChance;
    private final double chancePerLevel;
    private final double maxChance;
    private final int xpMultiplier;
    private final List<Entry> entries = new ArrayList<>();
    private final Random random = new Random();

    FishingLoot(ConfigurationSection c, Logger logger) {
        this.logger = logger;
        enabled = c != null && c.getBoolean("enabled", true);
        baseChance = c == null ? 0 : c.getDouble("base-chance", 5);
        chancePerLevel = c == null ? 0 : c.getDouble("chance-per-level", 2);
        maxChance = c == null ? 0 : c.getDouble("max-chance", 35);
        xpMultiplier = c == null ? 1 : Math.max(1, c.getInt("xp-multiplier", 10));
        if (c == null) return;
        for (Map<?, ?> raw : c.getMapList("loot")) {
            Entry e = new Entry();
            e.material = String.valueOf(raw.get("item")).toUpperCase(Locale.ROOT);
            e.minLevel = number(raw.get("level"), 0);
            e.weight = Math.max(1, number(raw.get("weight"), 10));
            String amount = raw.containsKey("amount") ? String.valueOf(raw.get("amount")) : "1";
            String[] parts = amount.split("-");
            e.minAmount = Math.max(1, number(parts[0].trim(), 1));
            e.maxAmount = Math.max(e.minAmount, parts.length > 1 ? number(parts[1].trim(), e.minAmount) : e.minAmount);
            e.enchantLevels = number(raw.get("enchant-levels"), 0);
            e.announce = Boolean.parseBoolean(String.valueOf(raw.get("announce")));
            e.name = raw.containsKey("name") ? String.valueOf(raw.get("name")) : null;
            if (!e.material.equals("TREASURE_MAP") && Material.matchMaterial(e.material) == null) {
                logger.warning("fishing-loot: onbekend item '" + e.material + "', overgeslagen.");
                continue;
            }
            entries.add(e);
        }
    }

    private static int number(Object value, int def) {
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? def : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** Vis-XP per vangst keer dit getal, zodat vis-levels (en dus betere loot) sneller gaan. */
    int xpMultiplier() {
        return xpMultiplier;
    }

    /** Kans (in procenten) op een bonusvangst bij dit level. */
    double chance(int level) {
        return enabled ? Math.min(maxChance, baseChance + chancePerLevel * level) : 0;
    }

    /** Na een gewone vangst: misschien iets extra's erbij, dat met de vangst mee naar je toe vliegt. */
    void onCatch(Player player, Item caught, int level) {
        if (!enabled || entries.isEmpty() || random.nextDouble() * 100 >= chance(level)) return;

        List<Entry> options = new ArrayList<>();
        int total = 0;
        for (Entry e : entries) {
            if (e.minLevel <= level) {
                options.add(e);
                total += e.weight;
            }
        }
        if (options.isEmpty()) return;
        int pick = random.nextInt(total);
        Entry chosen = options.get(options.size() - 1);
        for (Entry e : options) {
            pick -= e.weight;
            if (pick < 0) {
                chosen = e;
                break;
            }
        }

        ItemStack item = create(chosen, player.getLocation());
        if (item == null) return;
        Item bonus = caught.getWorld().dropItem(caught.getLocation(), item);
        bonus.setVelocity(caught.getVelocity());
        bonus.setOwner(player.getUniqueId());

        String label = chosen.name != null ? chosen.name : niceName(item.getType());
        String amount = item.getAmount() > 1 ? item.getAmount() + "x " : "";
        player.sendActionBar(LegacyComponentSerializer.legacyAmpersand().deserialize("&b&lBonusvangst! &f" + amount + label));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        if (chosen.announce) {
            Bukkit.broadcastMessage(ChatColor.AQUA + "" + ChatColor.BOLD + player.getName() + ChatColor.AQUA
                    + " viste " + ChatColor.WHITE + amount + label + ChatColor.AQUA + " op!");
        }
    }

    private ItemStack create(Entry e, Location near) {
        int amount = e.minAmount + random.nextInt(e.maxAmount - e.minAmount + 1);
        if (e.material.equals("TREASURE_MAP")) {
            World world = near.getWorld();
            try {
                // Dichtstbijzijnde onontdekte begraven schat, binnen 50 chunks (beperkt, tegen lag).
                ItemStack map = Bukkit.createExplorerMap(world, near, StructureType.BURIED_TREASURE,
                        MapCursor.Type.RED_X, 50, true);
                if (map != null) return map;
            } catch (Throwable ex) {
                logger.fine("Geen schatkaart: " + ex);
            }
            return new ItemStack(Material.GOLD_INGOT, 2 + random.nextInt(3)); // geen schat gevonden in de buurt
        }
        Material material = Material.matchMaterial(e.material);
        if (material == null) return null;
        ItemStack item = new ItemStack(material, Math.min(amount, material.getMaxStackSize()));
        if (e.enchantLevels > 0) {
            item = Bukkit.getItemFactory().enchantWithLevels(item, e.enchantLevels, true, random);
        }
        return item;
    }

    private static String niceName(Material material) {
        if (material == Material.FILLED_MAP) return "Schatkaart";
        if (material == Material.ENCHANTED_BOOK) return "Betoverd boek";
        if (material == Material.HEART_OF_THE_SEA) return "Hart van de zee";
        if (material == Material.ENCHANTED_GOLDEN_APPLE) return "Betoverde gouden appel";
        String name = material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
