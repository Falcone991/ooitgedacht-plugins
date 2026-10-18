package com.example.lobbyspawn;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * /survival opening [seconden] [npc]: aftellen voor iedereen (titel + geluid),
 * daarna gaat survival open en komt er vuurwerk uit de NPC('s) in de lobby.
 * De NPC's komen van OoitNPCs (label ooitnpcs:id); zonder NPC's komt het
 * vuurwerk bij het lobby-punt.
 */
final class SurvivalOpening implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Color[] COLORS = {
            Color.RED, Color.ORANGE, Color.YELLOW, Color.LIME, Color.AQUA, Color.BLUE, Color.PURPLE, Color.FUCHSIA, Color.WHITE
    };
    private static final int FIREWORK_WAVES = 8;   // aantal salvo's na het openen
    private static final long WAVE_TICKS = 12L;    // tijd tussen de salvo's

    private final LobbySpawn plugin;
    private final NamespacedKey npcKey = new NamespacedKey("ooitnpcs", "id");
    private final NamespacedKey fireworkKey;
    private final Random random = new Random();
    private BukkitTask task;

    SurvivalOpening(LobbySpawn plugin) {
        this.plugin = plugin;
        this.fireworkKey = new NamespacedKey(plugin, "opening_firework");
    }

    boolean running() {
        return task != null;
    }

    void stop(CommandSender sender) {
        if (task == null) {
            sender.sendMessage(ChatColor.YELLOW + "Er loopt geen opening.");
            return;
        }
        task.cancel();
        task = null;
        Bukkit.broadcastMessage(ChatColor.YELLOW + "De opening is gestopt.");
    }

    void start(CommandSender sender, int seconds, String npcId) {
        if (task != null) {
            sender.sendMessage(ChatColor.YELLOW + "De opening loopt al. Stoppen: /survival opening stop");
            return;
        }
        if (plugin.isSurvivalOpen()) {
            sender.sendMessage(ChatColor.YELLOW + "Survival is al open. Eerst dicht: /survival dicht");
            return;
        }
        if (fireworkOrigins(npcId).isEmpty()) {
            sender.sendMessage(ChatColor.RED + (npcId == null
                    ? "Geen NPC's of lobby-punt gevonden voor het vuurwerk. Stel de lobby in met /setlobby."
                    : "NPC '" + npcId + "' niet gevonden in de lobby. Gevonden: " + String.join(", ", fireworkOrigins(null).keySet())));
            return;
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + "" + ChatColor.BOLD + "Survival opent over " + seconds + " seconden!"
                + ChatColor.YELLOW + " Kom naar de lobby!");
        final int[] left = {seconds};
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0] <= 0) {
                task.cancel();
                task = null;
                if (!plugin.isSurvivalOpen()) plugin.setSurvivalOpen(true, sender.getName());
                fireworkShow(npcId);
                return;
            }
            countdownTick(left[0]);
            left[0]--;
        }, 0L, 20L);
    }

    private void countdownTick(int left) {
        if (left <= 10) {
            String color = left <= 3 ? "&c&l" : left <= 5 ? "&6&l" : "&e&l";
            Title title = Title.title(LEGACY.deserialize(color + left), LEGACY.deserialize("&7Survival gaat bijna open..."),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100)));
            float pitch = left <= 3 ? 2.0f : 1.0f;
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(title);
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
            }
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.sendActionBar(LEGACY.deserialize("&eSurvival opent over &6&l" + left + " &eseconden"));
            }
            if (left % 30 == 0) {
                Bukkit.broadcastMessage(ChatColor.YELLOW + "Survival opent over " + left + " seconden!");
            }
        }
    }

    /** Een paar salvo's vuurwerk uit elke NPC (of uit één NPC als die gekozen is). */
    private void fireworkShow(String npcId) {
        final int[] wave = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, t -> {
            if (wave[0]++ >= FIREWORK_WAVES) {
                t.cancel();
                return;
            }
            for (Location origin : fireworkOrigins(npcId).values()) {
                int count = 2 + random.nextInt(2);
                for (int i = 0; i < count; i++) launch(origin);
            }
        }, 0L, WAVE_TICKS);
    }

    private void launch(Location origin) {
        Location at = origin.clone().add(random.nextDouble() * 3 - 1.5, 1.0, random.nextDouble() * 3 - 1.5);
        at.getWorld().spawn(at, Firework.class, fw -> {
            FireworkMeta meta = fw.getFireworkMeta();
            FireworkEffect.Type[] types = FireworkEffect.Type.values();
            meta.addEffect(FireworkEffect.builder()
                    .with(types[random.nextInt(types.length)])
                    .withColor(COLORS[random.nextInt(COLORS.length)], COLORS[random.nextInt(COLORS.length)])
                    .withFade(COLORS[random.nextInt(COLORS.length)])
                    .flicker(random.nextBoolean())
                    .trail(random.nextBoolean())
                    .build());
            meta.setPower(1 + random.nextInt(2));
            fw.setFireworkMeta(meta);
            fw.getPersistentDataContainer().set(fireworkKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /**
     * Waar het vuurwerk vandaan komt: per NPC in de lobby-wereld één punt
     * (gekozen NPC = alleen die). Geen NPC's gevonden en geen keuze? Dan het lobby-punt.
     */
    private Map<String, Location> fireworkOrigins(String npcId) {
        Map<String, Location> origins = new LinkedHashMap<>();
        Location lobby = plugin.lobbyLocation();
        if (lobby == null) return origins;
        List<Entity> entities = new ArrayList<>(lobby.getWorld().getEntities());
        for (Entity e : entities) {
            String id = e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
            if (id == null || (npcId != null && !id.equalsIgnoreCase(npcId))) continue;
            // Voorwerp-NPC bestaat uit meerdere delen; de klikbox (of mannequin) staat onderaan.
            boolean main = e.getType().name().equals("MANNEQUIN") || e.getType() == EntityType.INTERACTION;
            if (main || !origins.containsKey(id)) origins.put(id.toLowerCase(Locale.ROOT), e.getLocation());
        }
        if (origins.isEmpty() && npcId == null) origins.put("lobby", lobby);
        return origins;
    }

    /** Ons eigen vuurwerk doet niemand pijn. */
    @EventHandler(ignoreCancelled = true)
    public void onFireworkDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Firework
                && event.getDamager().getPersistentDataContainer().has(fireworkKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
        }
    }
}
