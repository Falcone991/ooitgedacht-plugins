package com.example.survivalextras;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Teleport met wachttijd: de speler moet een paar seconden stilstaan (aftellen
 * onderin beeld, oplopende piepjes, portaal-deeltjes). Bewegen of schade
 * krijgen breekt het af. Gedeeld door /tpa en /home.
 */
final class Teleports implements Listener {

    private static final class Pending {
        final Location start;
        final String label;
        BukkitTask task;

        Pending(Location start, String label) {
            this.start = start;
            this.label = label;
        }
    }

    private final JavaPlugin plugin;
    private final Map<UUID, Pending> pending = new HashMap<>();

    Teleports(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    static boolean bypass(Player player) {
        return player.hasPermission("survivalextras.bypassdelay");
    }

    /**
     * @param label       bestemming voor in de berichten, bv. "Steve" of "home 'basis'"
     * @param destination wordt pas aan het eind van het aftellen opgevraagd (een speler kan
     *                    intussen bewogen zijn); null = gaat niet door (de supplier meldt zelf waarom)
     * @param onSuccess   na een geslaagde teleport, bv. om de cooldown te starten
     */
    void teleport(Player player, String label, int warmupSeconds, Supplier<Location> destination, Runnable onSuccess) {
        UUID uuid = player.getUniqueId();
        if (pending.containsKey(uuid)) {
            player.sendMessage(ChatColor.YELLOW + "Je bent al aan het teleporteren, blijf stilstaan...");
            return;
        }
        if (warmupSeconds <= 0 || bypass(player)) {
            execute(player, label, destination, onSuccess);
            return;
        }

        Pending p = new Pending(player.getLocation(), label);
        pending.put(uuid, p);
        player.sendMessage(ChatColor.YELLOW + "Je wordt over " + warmupSeconds + " seconden naar "
                + ChatColor.WHITE + label + ChatColor.YELLOW + " geteleporteerd. Blijf stilstaan!");

        int[] left = {warmupSeconds};
        p.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0] <= 0) {
                pending.remove(uuid);
                p.task.cancel();
                execute(player, label, destination, onSuccess);
                return;
            }
            player.sendActionBar(SurvivalExtras.legacy("&eTeleporteren naar &f" + label + " &eover &f&l" + left[0] + "&e..."));
            float pitch = 0.6f + 1.2f * (warmupSeconds - left[0]) / warmupSeconds;
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.7f, pitch);
            player.getWorld().spawnParticle(Particle.PORTAL, player.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.3);
            left[0]--;
        }, 0L, 20L);
    }

    private void execute(Player player, String label, Supplier<Location> destination, Runnable onSuccess) {
        if (!player.isOnline()) return;
        Location dest = destination.get();
        if (dest == null) return;

        Location from = player.getLocation();
        player.teleportAsync(dest).thenAccept(ok -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!ok) {
                player.sendMessage(ChatColor.RED + "Teleporteren is mislukt. Zit je misschien op een dier of in een boot?");
                return;
            }
            from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.0f);
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            player.sendActionBar(Component.empty());
            player.sendMessage(ChatColor.GREEN + "Je bent naar " + ChatColor.WHITE + label + ChatColor.GREEN + " geteleporteerd.");
            onSuccess.run();
        }));
    }

    private void cancel(Player player, String reason) {
        Pending p = pending.remove(player.getUniqueId());
        if (p == null) return;
        p.task.cancel();
        if (reason == null) return;
        player.sendActionBar(Component.empty());
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
        player.sendMessage(ChatColor.RED + "Teleport naar " + p.label + " geannuleerd: " + reason);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending p = pending.get(event.getPlayer().getUniqueId());
        if (p == null) return;
        // Alleen echt verplaatsen telt, rondkijken mag.
        Location to = event.getTo();
        if (to.getWorld() != p.start.getWorld()
                || to.getBlockX() != p.start.getBlockX()
                || to.getBlockY() != p.start.getBlockY()
                || to.getBlockZ() != p.start.getBlockZ()) {
            cancel(event.getPlayer(), "je bewoog.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            cancel((Player) event.getEntity(), "je kreeg schade.");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer(), null);
    }
}
