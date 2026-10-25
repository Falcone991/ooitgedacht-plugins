package com.example.lobbyspawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerListPingEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Tekst in de serverlijst: bovenaan een vaste regel, daaronder elke keer een willekeurig
 * grappig zinnetje met namen van echte spelers ("<speler> is een rare snuiter!").
 * Teksten in MiniMessage-opmaak (kleuren en kleurverlopen), zie motd in config.yml.
 *
 * Let op: slaapt de server (AMP), dan antwoordt AMP zelf en zie je de tekst van AMP.
 */
final class Motd implements Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final String firstLine;
    private final List<String> lines;
    private final List<String> fallback;
    private final Random random = new Random();

    // Namen van spelers die ooit op de server zijn geweest; af en toe bijgewerkt.
    private List<String> known = new ArrayList<>();
    private long knownAt;

    Motd(ConfigurationSection c) {
        firstLine = c.getString("first-line", "<gradient:#ff8a00:#ffd000><bold>OoitGedacht SMP</bold></gradient>");
        lines = c.getStringList("lines");
        fallback = c.getStringList("lines-without-players");
    }

    @EventHandler
    public void onPing(ServerListPingEvent event) {
        List<String> names = names();
        String template;
        if (names.size() >= 2 && !lines.isEmpty()) {
            template = lines.get(random.nextInt(lines.size()));
        } else if (!fallback.isEmpty()) {
            template = fallback.get(random.nextInt(fallback.size()));
        } else {
            template = "<gray>Kom je ook spelen?</gray>";
        }
        String a = names.isEmpty() ? "Steve" : names.get(random.nextInt(names.size()));
        String b = a;
        for (int i = 0; i < 10 && b.equals(a) && names.size() >= 2; i++) b = names.get(random.nextInt(names.size()));

        Component line1 = MM.deserialize(firstLine);
        Component line2 = MM.deserialize(template,
                Placeholder.unparsed("speler", a),
                Placeholder.unparsed("speler2", b));
        event.motd(line1.append(Component.newline()).append(line2));
    }

    /** Online spelers vaker, en verder iedereen die ooit is geweest. */
    private List<String> names() {
        long now = System.currentTimeMillis();
        if (now - knownAt > 300_000L) {
            List<String> all = new ArrayList<>();
            for (OfflinePlayer p : Bukkit.getOfflinePlayers()) if (p.getName() != null) all.add(p.getName());
            known = all;
            knownAt = now;
        }
        List<String> names = new ArrayList<>(known);
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName()); // online spelers dubbel = grotere kans
            if (!known.contains(p.getName())) names.add(p.getName());
        }
        Collections.shuffle(names, random);
        return names;
    }
}
