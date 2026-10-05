package com.example.ooitnpcs;

import io.papermc.paper.entity.LookAnchor;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Klikken, onkwetsbaar houden, kwijtgeraakte NPC's terugzetten, meekijken en ronddraaien. */
final class NpcListener implements Listener {

    private static final double LOOK_RADIUS = 8.0;
    private static final long CLICK_COOLDOWN_MS = 1000L;
    private static final int SPIN_STEP_DEGREES = 30;   // per seconde; vloeiend dankzij interpolatie

    private final OoitNpcs plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    NpcListener(OoitNpcs plugin) {
        this.plugin = plugin;
    }

    private Npc npcOf(Entity entity) {
        String id = plugin.idOf(entity);
        return id == null ? null : plugin.npcs.get(id);
    }

    // ---------- Klikken (rechts én links) ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClick(PlayerInteractEntityEvent event) {
        if (plugin.idOf(event.getRightClicked()) == null) return;
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        click(event.getPlayer(), event.getRightClicked());
    }

    /** Linksklikken (slaan) telt ook als klik; veel spelers doen dat. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (plugin.idOf(event.getAttacked()) == null) return;
        event.setCancelled(true);
        click(event.getPlayer(), event.getAttacked());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.idOf(event.getEntity()) == null) return;
        event.setCancelled(true);
        if (event instanceof EntityDamageByEntityEvent
                && ((EntityDamageByEntityEvent) event).getDamager() instanceof Player) {
            click((Player) ((EntityDamageByEntityEvent) event).getDamager(), event.getEntity());
        }
    }

    private void click(Player player, Entity entity) {
        if (player.getGameMode() == GameMode.SPECTATOR) return;
        Npc npc = npcOf(entity);
        if (npc == null) return;

        long now = System.currentTimeMillis();
        Long last = lastClick.get(player.getUniqueId());
        if (last != null && now - last < CLICK_COOLDOWN_MS) return;
        lastClick.put(player.getUniqueId(), now);

        if (npc.action == Npc.ActionType.GEEN) return;
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
        plugin.runAction(npc, player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    // ---------- Kwijtgeraakte / dubbele NPC's ----------

    /** /kill negeert onkwetsbaarheid; dan meteen een nieuwe neerzetten. */
    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        Npc npc = npcOf(event.getEntity());
        if (npc == null) return;
        event.getDrops().clear();
        event.setDroppedExp(0);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (plugin.npcs.get(npc.id) == npc && plugin.entityOf(npc) == null) plugin.spawn(npc);
        });
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        check(event.getChunk(), event.getEntities());
    }

    /**
     * Na het laden van een stuk wereld: delen die nergens meer bij horen gaan weg,
     * en een NPC die hier hoort maar (deels) kwijt is wordt opnieuw neergezet.
     */
    void check(Chunk chunk, List<Entity> entities) {
        Map<String, List<Entity>> byNpc = new HashMap<>();
        for (Entity entity : entities) {
            String id = plugin.idOf(entity);
            if (id == null) continue;
            if (!plugin.npcs.containsKey(id)) {
                entity.remove();
                continue;
            }
            byNpc.computeIfAbsent(id, k -> new ArrayList<>()).add(entity);
        }

        for (Npc npc : plugin.npcs.values()) {
            boolean belongsHere = npc.inChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
            List<Entity> parts = byNpc.getOrDefault(npc.id, new ArrayList<>());
            if (!belongsHere && parts.isEmpty()) continue;

            boolean mainFound = false;
            boolean displayFound = false;
            for (Entity e : parts) {
                if (e.getUniqueId().equals(npc.entity)) mainFound = true;
                if (e instanceof ItemDisplay) {
                    displayFound = true;
                    npc.display = e.getUniqueId();
                }
            }
            boolean complete = mainFound && (npc.kind == Npc.Kind.SPELER || displayFound);

            if (complete && belongsHere) {
                // Dubbele hoofd-entity's (oude kopieën) opruimen.
                for (Entity e : parts) {
                    if ((e instanceof Mannequin || e instanceof Interaction) && !e.getUniqueId().equals(npc.entity)) e.remove();
                }
            } else {
                for (Entity e : parts) e.remove();
                if (belongsHere && plugin.entityOf(npc) == null) plugin.spawn(npc);
            }
        }
    }

    // ---------- Meekijken (speler-NPC's) ----------

    /** Elke 4 ticks: speler-NPC's draaien hun hoofd naar de dichtstbijzijnde speler. */
    void lookTick() {
        for (Npc npc : plugin.npcs.values()) {
            if (npc.kind != Npc.Kind.SPELER || !npc.look) continue;
            Entity entity = plugin.entityOf(npc);
            if (!(entity instanceof Mannequin)) continue;
            Mannequin m = (Mannequin) entity;

            Player nearest = null;
            double best = LOOK_RADIUS * LOOK_RADIUS;
            Location here = m.getLocation();
            for (Player p : m.getWorld().getPlayers()) {
                if (p.getGameMode() == GameMode.SPECTATOR) continue;
                double d = p.getLocation().distanceSquared(here);
                if (d < best) {
                    best = d;
                    nearest = p;
                }
            }
            if (nearest != null) {
                m.lookAt(nearest.getEyeLocation(), LookAnchor.EYES);
            } else if (Math.abs(here.getYaw() - npc.yaw) > 0.5f || Math.abs(here.getPitch() - npc.pitch) > 0.5f) {
                m.setRotation(npc.yaw, npc.pitch);
            }
        }
    }

    // ---------- Ronddraaien (voorwerp-NPC's) ----------

    /** Elke seconde een stukje verder draaien; de client maakt het vloeiend. */
    void spinTick() {
        for (Npc npc : plugin.npcs.values()) {
            if (npc.kind != Npc.Kind.ITEM || !npc.spin || npc.display == null) continue;
            Entity entity = Bukkit.getEntity(npc.display);
            if (!(entity instanceof ItemDisplay)) continue;
            ItemDisplay display = (ItemDisplay) entity;

            npc.spinAngle = (npc.spinAngle + SPIN_STEP_DEGREES) % 360;
            Transformation t = display.getTransformation();
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(20);
            display.setTransformation(new Transformation(t.getTranslation(),
                    new Quaternionf().rotationY((float) Math.toRadians(npc.spinAngle)), t.getScale(), t.getRightRotation()));
        }
    }
}
