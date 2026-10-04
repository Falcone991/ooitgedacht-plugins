package com.example.survivalextras;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBedLeaveEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Nacht overslaan als genoeg spelers slapen (standaard de helft), met een
 * "Dikke doei!"-titel en slaapliedje voor de slaper, en een ochtend-melodie
 * plus haan (kip) voor iedereen.
 *
 * De tijd springt net als in vanilla naar de volgende ochtend, dus de
 * dagteller (en daarmee de wereldgrens van SurvivalTimeline) loopt gewoon door.
 */
final class SleepSkip implements Listener {

    private static final Title.Times TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(1000));

    private final JavaPlugin plugin;
    private final int percentage;
    private final long delayTicks;
    private final boolean sounds;
    private final String bedTitle, bedSubtitle, bedMessage, actionbar;
    private final String morningTitle, morningSubtitle, morningMessage;

    private final Set<UUID> skipScheduled = new HashSet<>();     // wereld-ID's
    private final Map<UUID, Long> lastMorning = new HashMap<>();  // wereld-ID -> tijdstip

    SleepSkip(JavaPlugin plugin) {
        this.plugin = plugin;
        FileConfiguration c = plugin.getConfig();
        percentage = Math.max(1, Math.min(100, c.getInt("sleep.percentage", 50)));
        // Max. 4 s: vanilla slaat zelf over na 5 s als echt iedereen slaapt, en wij willen eerst zijn.
        delayTicks = Math.max(1, Math.min(4, c.getInt("sleep.skip-delay-seconds", 3))) * 20L;
        sounds = c.getBoolean("sleep.sounds", true);
        bedTitle = c.getString("sleep.bed-title", "&9Dikke doei!");
        bedSubtitle = c.getString("sleep.bed-subtitle", "&7Goeie nachtrust");
        bedMessage = c.getString("sleep.bed-message", "&f{player} &7gaat slapen. Dikke doei, goeie nachtrust! &8({sleeping}/{needed})");
        actionbar = c.getString("sleep.actionbar", "&7{sleeping}/{needed} spelers slapen");
        morningTitle = c.getString("sleep.morning-title", "&6Goedemorgen!");
        morningSubtitle = c.getString("sleep.morning-subtitle", "&eDe nacht is overgeslagen");
        morningMessage = c.getString("sleep.morning-message", "&eGoedemorgen allemaal! De nacht is overgeslagen.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (event.getBedEnterResult() != PlayerBedEnterEvent.BedEnterResult.OK) return;
        Player player = event.getPlayer();
        World world = player.getWorld();

        player.showTitle(Title.title(SurvivalExtras.legacy(bedTitle), SurvivalExtras.legacy(bedSubtitle), TITLE_TIMES));
        if (sounds) lullaby(player);

        // De speler ligt pas een tick later echt in bed.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline() || !player.isSleeping()) return;
            String message = fill(bedMessage.replace("{player}", player.getName()), world);
            for (Player p : world.getPlayers()) {
                p.sendMessage(SurvivalExtras.legacy(message));
                if (sounds && p != player) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.4f, 0.7f);
            }
            check(world);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBedLeave(PlayerBedLeaveEvent event) {
        World world = event.getPlayer().getWorld();
        // Een tick later is de speler echt uit bed en klopt de telling.
        Bukkit.getScheduler().runTask(plugin, () -> showCount(world));
    }

    private void check(World world) {
        showCount(world);
        int sleeping = sleeping(world);
        if (sleeping == 0 || sleeping < needed(world)) return;
        if (!skipScheduled.add(world.getUID())) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            skipScheduled.remove(world.getUID());
            int now = sleeping(world);
            if (now == 0 || now < needed(world)) return;

            // Zelfde als vanilla: naar het begin van de volgende dag.
            long time = world.getFullTime() + 24000L;
            world.setFullTime(time - time % 24000L);
            world.setStorm(false);
            world.setThundering(false);
            morning(world);
        }, delayTicks);
    }

    private void showCount(World world) {
        if (sleeping(world) == 0) return;
        String text = fill(actionbar, world);
        for (Player p : world.getPlayers()) p.sendActionBar(SurvivalExtras.legacy(text));
    }

    private void morning(World world) {
        long now = System.currentTimeMillis();
        Long last = lastMorning.get(world.getUID());
        if (last != null && now - last < 10_000L) return;
        lastMorning.put(world.getUID(), now);

        Title title = Title.title(SurvivalExtras.legacy(morningTitle), SurvivalExtras.legacy(morningSubtitle), TITLE_TIMES);
        for (Player p : world.getPlayers()) {
            p.showTitle(title);
            p.sendMessage(SurvivalExtras.legacy(morningMessage));
        }
        if (sounds) morningTune(world);
    }

    // ---------- Tellen ----------

    /** Spelers die meetellen: geen toeschouwers (dood in hardcore) en niet "genegeerd" door de server. */
    private static boolean counts(Player p) {
        return p.getGameMode() != GameMode.SPECTATOR && !p.isSleepingIgnored();
    }

    private static int sleeping(World world) {
        int n = 0;
        for (Player p : world.getPlayers()) if (counts(p) && p.isSleeping()) n++;
        return n;
    }

    private int needed(World world) {
        int total = 0;
        for (Player p : world.getPlayers()) if (counts(p)) total++;
        return Math.max(1, (int) Math.ceil(total * percentage / 100.0));
    }

    private String fill(String text, World world) {
        return text.replace("{sleeping}", String.valueOf(sleeping(world)))
                .replace("{needed}", String.valueOf(needed(world)));
    }

    // ---------- Geluidjes ----------

    /** Toonhoogte van noot n op een nootblok (0 t/m 24, 12 = normaal). */
    private static float note(int n) {
        return (float) Math.pow(2.0, (n - 12) / 12.0);
    }

    /** Rustig dalend slaapliedje, alleen voor de slaper. */
    private void lullaby(Player player) {
        List<Integer> notes = List.of(18, 13, 10, 6);
        for (int i = 0; i < notes.size(); i++) {
            float pitch = note(notes.get(i));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.5f, pitch);
            }, i * 6L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline()) player.playSound(player.getLocation(), Sound.ENTITY_CAT_PURR, 0.8f, 1.0f);
        }, 28L);
    }

    /** Vrolijk oplopend ochtend-deuntje, en dan de "haan". */
    private void morningTune(World world) {
        List<Integer> notes = List.of(6, 10, 13, 18);
        for (int i = 0; i < notes.size(); i++) {
            float pitch = note(notes.get(i));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Player p : world.getPlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 0.8f, pitch);
            }, i * 4L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Player p : world.getPlayers()) {
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.2f);
                p.playSound(p.getLocation(), Sound.ENTITY_CHICKEN_AMBIENT, 1.0f, 0.8f);
            }
        }, 20L);
    }
}
