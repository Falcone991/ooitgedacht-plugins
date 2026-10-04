package com.example.ooitworlds;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Past de regels van elke wereld toe (zie WorldSettings).
 *
 * Gamemode: wie een wereld met een vaste gamemode binnenkomt, krijgt die; de
 * oude gamemode wordt op de speler zelf bewaard en teruggezet zodra die weer
 * in een wereld zonder vaste gamemode komt. Toeschouwers (dood in hardcore)
 * en OP's worden nooit aangepast, zodat niemand per ongeluk weer leeft.
 */
final class WorldRules implements Listener {

    private final OoitWorlds plugin;
    private final NamespacedKey previousGameModeKey;

    WorldRules(OoitWorlds plugin) {
        this.plugin = plugin;
        this.previousGameModeKey = new NamespacedKey(plugin, "previous_gamemode");
    }

    private WorldSettings settings(World world) {
        return plugin.settings(world.getName());
    }

    private boolean flag(World world, String flag) {
        WorldSettings s = settings(world);
        return s == null || s.flag(flag);
    }

    /** Elke seconde: tijd vastzetten in werelden met altijd-dag. */
    void tick() {
        for (World world : Bukkit.getWorlds()) {
            WorldSettings s = settings(world);
            if (s == null) continue;
            // Terug naar 12:00 van dezelfde dag (setTime springt vooruit naar de volgende dag).
            if (s.flag("altijd-dag") && world.getTime() != 6000L) {
                long full = world.getFullTime();
                world.setFullTime(full - Math.floorMod(full, 24000L) + 6000L);
            }
            if (s.flag("geen-regen") && world.hasStorm()) {
                world.setStorm(false);
                world.setThundering(false);
            }
        }
    }

    // ---------- Gamemode ----------

    /** Gamemode goed zetten voor de wereld waar de speler nu is. */
    void applyGameMode(Player player) {
        if (player.hasPermission("ooitworlds.admin")) return;
        if (player.getGameMode() == GameMode.SPECTATOR) return;

        PersistentDataContainer pdc = player.getPersistentDataContainer();
        WorldSettings s = settings(player.getWorld());
        GameMode wanted = s == null ? null : s.gamemode;

        if (wanted != null) {
            if (!pdc.has(previousGameModeKey, PersistentDataType.STRING)) {
                pdc.set(previousGameModeKey, PersistentDataType.STRING, player.getGameMode().name());
            }
            if (player.getGameMode() != wanted) player.setGameMode(wanted);
            return;
        }

        String previous = pdc.get(previousGameModeKey, PersistentDataType.STRING);
        if (previous == null) return;
        pdc.remove(previousGameModeKey);
        GameMode old = WorldSettings.parseGameMode(previous);
        if (old != null && player.getGameMode() != old) player.setGameMode(old);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        applyGameMode(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        applyGameMode(event.getPlayer());
    }

    // ---------- Schade, pvp, honger, void ----------

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();
        World world = player.getWorld();

        if (event.getCause() == EntityDamageEvent.DamageCause.VOID && flag(world, "void-terug")) {
            event.setCancelled(true);
            toSpawn(player);
            return;
        }
        if (!flag(world, "schade")) {
            event.setCancelled(true);
            return;
        }
        if (!flag(world, "pvp") && event instanceof EntityDamageByEntityEvent
                && attacker(((EntityDamageByEntityEvent) event).getDamager()) != null) {
            event.setCancelled(true);
        }
    }

    /** De speler achter een aanval (ook pijlen en dergelijke), of null. */
    private static Player attacker(Entity damager) {
        if (damager instanceof Player) return (Player) damager;
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            return (Player) ((Projectile) damager).getShooter();
        }
        return null;
    }

    @EventHandler(ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (flag(event.getEntity().getWorld(), "honger")) return;
        if (event.getFoodLevel() < event.getEntity().getFoodLevel()) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to.getY() >= to.getWorld().getMinHeight() - 5) return;
        if (flag(to.getWorld(), "void-terug")) toSpawn(event.getPlayer());
    }

    private void toSpawn(Player player) {
        player.setFallDistance(0);
        player.teleportAsync(OoitWorlds.spawn(player.getWorld()));
    }

    // ---------- Mobs ----------

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (flag(event.getLocation().getWorld(), "mobs")) return;
        switch (event.getSpawnReason()) {
            case SPAWNER_EGG:
            case COMMAND:
            case CUSTOM:
                return; // bewust neergezet door beheer
            default:
                break;
        }
        if (event.getEntity() instanceof Enemy || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.NATURAL) {
            event.setCancelled(true);
        }
    }

    // ---------- Bouwen ----------

    private boolean mayBuild(Player player) {
        return player.hasPermission("ooitworlds.admin") || flag(player.getWorld(), "bouwen");
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (!mayBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!mayBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!mayBuild(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (!mayBuild(event.getPlayer())) event.setCancelled(true);
    }

    // ---------- Portalen en weer ----------

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (!flag(event.getFrom().getWorld(), "portalen")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        if (!flag(event.getFrom().getWorld(), "portalen")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (event.toWeatherState() && flag(event.getWorld(), "geen-regen")) event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onThunder(ThunderChangeEvent event) {
        if (event.toThunderState() && flag(event.getWorld(), "geen-regen")) event.setCancelled(true);
    }
}
