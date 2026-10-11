package com.example.survivalextras;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.inventory.PrepareSmithingEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Uitgeschakelde wapens (standaard de mace en alle speren). Ze zijn nooit te
 * craften. Tenzij 'only-crafting' aan staat ook: niet te gebruiken, niet op te
 * pakken, mobs krijgen ze niet in handen, en wie er al een heeft raakt hem kwijt
 * bij inloggen of bij het sluiten van een kist.
 */
final class BannedItems implements Listener {

    private static final List<String> DEFAULT_ITEMS = Arrays.asList("mace",
            "wooden_spear", "stone_spear", "copper_spear", "iron_spear", "golden_spear", "diamond_spear", "netherite_spear");

    private final Set<Material> banned = EnumSet.noneOf(Material.class);
    private final boolean onlyCrafting;
    private final Component message;
    private final Map<UUID, Long> lastMessage = new HashMap<>();

    BannedItems(JavaPlugin plugin) {
        FileConfiguration c = plugin.getConfig();
        List<String> names = c.isList("banned-items.items") ? c.getStringList("banned-items.items") : DEFAULT_ITEMS;
        for (String name : names) {
            // Op naam opzoeken: de speren bestaan pas sinds Minecraft 1.21.11.
            Material material = Material.matchMaterial(name.trim());
            if (material == null) plugin.getLogger().warning("Onbekend item in banned-items.items: '" + name + "'");
            else banned.add(material);
        }
        onlyCrafting = c.getBoolean("banned-items.only-crafting", false);
        message = SurvivalExtras.legacy(c.getString("banned-items.message", "&cDit wapen is uitgeschakeld op deze server."));
    }

    int count() {
        return banned.size();
    }

    boolean onlyCrafting() {
        return onlyCrafting;
    }

    private boolean isBanned(ItemStack item) {
        return item != null && banned.contains(item.getType());
    }

    /** Geldt het verbod op gebruiken/oppakken/hebben voor deze speler? */
    private boolean restricted(Player player) {
        return !onlyCrafting && !player.hasPermission("survivalextras.banneditems.bypass");
    }

    /** Melding onderin beeld, hooguit eens per 2 seconden. */
    private void notify(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastMessage.get(player.getUniqueId());
        if (last != null && now - last < 2000L) return;
        lastMessage.put(player.getUniqueId(), now);
        player.sendActionBar(message);
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.7f, 0.6f);
    }

    // ---------- Craften (altijd geblokkeerd) ----------

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        if (isBanned(event.getInventory().getResult())) event.getInventory().setResult(null);
    }

    /** Smeedtafel, bv. diamanten speer -> netherite speer. */
    @EventHandler
    public void onSmith(PrepareSmithingEvent event) {
        if (isBanned(event.getResult())) event.setResult(null);
    }

    /** Het automatische crafter-blok. */
    @EventHandler
    public void onCrafter(CrafterCraftEvent event) {
        if (isBanned(event.getResult())) event.setCancelled(true);
    }

    // ---------- Gebruiken ----------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (onlyCrafting || !(event.getDamager() instanceof LivingEntity)) return;
        LivingEntity attacker = (LivingEntity) event.getDamager();
        EntityEquipment equipment = attacker.getEquipment();
        if (equipment == null || !isBanned(equipment.getItemInMainHand())) return;
        if (attacker instanceof Player && !restricted((Player) attacker)) return;
        event.setCancelled(true);
        if (attacker instanceof Player) notify((Player) attacker);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isBanned(event.getItem()) || !restricted(event.getPlayer())) return;
        event.setUseItemInHand(Event.Result.DENY);
        notify(event.getPlayer());
    }

    // ---------- Krijgen ----------

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (onlyCrafting || !isBanned(event.getItem().getItemStack())) return;
        if (event.getEntity() instanceof Player) {
            Player player = (Player) event.getEntity();
            if (!restricted(player)) return;
            event.getItem().remove();
            notify(player);
        }
        event.setCancelled(true); // ook mobs pakken ze niet op
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        purge(event.getPlayer());
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) purge((Player) event.getPlayer());
    }

    /** Uitgeschakelde wapens uit de inventory halen. */
    private void purge(Player player) {
        if (!restricted(player)) return;
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        int removed = 0;
        for (int i = 0; i < contents.length; i++) {
            if (isBanned(contents[i])) {
                inv.setItem(i, null);
                removed++;
            }
        }
        if (removed > 0) {
            player.sendMessage(SurvivalExtras.legacy("&c" + removed + " uitgeschakeld(e) wapen(s) uit je inventory gehaald."));
        }
    }

    /** Mobs (bv. zombies met een speer) krijgen ze niet in handen. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent event) {
        if (onlyCrafting) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof LivingEntity)) return;
        EntityEquipment equipment = ((LivingEntity) entity).getEquipment();
        if (equipment == null) return;
        if (isBanned(equipment.getItemInMainHand())) equipment.setItemInMainHand(null);
        if (isBanned(equipment.getItemInOffHand())) equipment.setItemInOffHand(null);
    }
}
