package com.example.stafftools;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * /announce <bericht>: groot bericht voor iedereen (chat, titel en geluid).
 * /restart [tijd] [reden]: aftellen met een balk bovenin beeld en meldingen, daarna
 * iedereen netjes eruit en de server stopt (AMP start hem weer). /restart stop = afbreken.
 */
final class ServerControl implements TabExecutor {

    private static final String PERMISSION = "stafftools.staff";
    private static final List<Integer> NOTICES = Arrays.asList(600, 300, 180, 120, 60, 30, 15, 10, 5, 4, 3, 2, 1);

    private final JavaPlugin plugin;
    private BukkitTask restartTask;
    private BossBar bar;

    ServerControl(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (command.getName().equalsIgnoreCase("announce")) {
            announce(sender, args);
        } else {
            restart(sender, args);
        }
        return true;
    }

    // ---------- /announce ----------

    private void announce(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /announce <bericht>  (& voor kleuren)");
            return;
        }
        String message = String.join(" ", args);
        Component text = legacy("&f" + message);
        Bukkit.broadcast(legacy("&6&m                                                  "));
        Bukkit.broadcast(legacy("&6&l  MEDEDELING"));
        Bukkit.broadcast(Component.text("  ").append(text));
        Bukkit.broadcast(legacy("&6&m                                                  "));
        Title title = Title.title(legacy("&6&lMEDEDELING"), text,
                Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(1)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.showTitle(title);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1f);
        }
        plugin.getLogger().info("[Announce] " + sender.getName() + ": " + message);
    }

    // ---------- /restart ----------

    private void restart(CommandSender sender, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("stop")) {
            if (restartTask == null) {
                sender.sendMessage(ChatColor.YELLOW + "Er loopt geen herstart.");
                return;
            }
            cancel();
            Bukkit.broadcast(legacy("&a&lDe herstart gaat niet door."));
            return;
        }
        if (restartTask != null) {
            sender.sendMessage(ChatColor.YELLOW + "Er loopt al een herstart. Afbreken: /restart stop");
            return;
        }
        int seconds = 60;
        int reasonFrom = 0;
        if (args.length >= 1) {
            Long parsed = Brig.parseTime(args[0]);
            if (parsed != null) {
                seconds = (int) (parsed / 1000);
                reasonFrom = 1;
            }
        }
        if (seconds < 5 || seconds > 3600) {
            sender.sendMessage(ChatColor.RED + "Kies tussen 5 seconden en 1 uur (bv. 30s, 1m, 5m).");
            return;
        }
        String reason = String.join(" ", Arrays.copyOfRange(args, reasonFrom, args.length));
        plugin.getLogger().info("[Restart] " + sender.getName() + " herstart de server over " + seconds + " s"
                + (reason.isEmpty() ? "" : " (" + reason + ")") + ".");

        final int total = seconds;
        final int[] left = {seconds};
        bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        Bukkit.broadcast(legacy("&c&lDe server herstart over " + Brig.format(seconds * 1000L) + "!"
                + (reason.isEmpty() ? "" : " &7(" + reason + ")")));
        restartTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (left[0] <= 0) {
                cancel();
                shutdown(reason);
                return;
            }
            bar.name(legacy("&c&lServer herstart over &f" + Brig.format(left[0] * 1000L)));
            bar.progress(Math.max(0f, Math.min(1f, left[0] / (float) total)));
            for (Player p : Bukkit.getOnlinePlayers()) p.showBossBar(bar);
            if (NOTICES.contains(left[0]) && left[0] != total) {
                Bukkit.broadcast(legacy("&cDe server herstart over &f" + Brig.format(left[0] * 1000L) + "&c."));
            }
            if (left[0] <= 10) {
                Title title = Title.title(legacy("&c&l" + left[0]), legacy("&7Server herstart..."),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ofMillis(100)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, left[0] <= 3 ? 2f : 1f);
                }
            }
            left[0]--;
        }, 0L, 20L);
    }

    private void cancel() {
        if (restartTask != null) restartTask.cancel();
        restartTask = null;
        if (bar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) p.hideBossBar(bar);
            bar = null;
        }
    }

    private void shutdown(String reason) {
        Component kick = legacy("&e&lDe server herstart!\n\n&fOver ongeveer een minuut ben je weer welkom."
                + (reason.isEmpty() ? "" : "\n&7" + reason));
        for (Player p : Bukkit.getOnlinePlayers()) p.kick(kick);
        // Even wachten zodat iedereen het bericht krijgt; daarna netjes opslaan en stoppen.
        Bukkit.getScheduler().runTaskLater(plugin, Bukkit::shutdown, 20L);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || command.getName().equalsIgnoreCase("announce") || args.length != 1) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String option : Arrays.asList("30s", "1m", "5m", "10m", "stop")) {
            if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(option);
        }
        return result;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
