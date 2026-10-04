package com.example.survivalextras;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /tpa (jij gaat naar de ander) en /tpahere (de ander komt naar jou).
 * De ontvanger klikt op [Accepteren] of [Weigeren]; daarna moet degene die
 * verplaatst wordt een paar seconden stilstaan (zie Teleports).
 *
 * Toeschouwers (dood in hardcore) kunnen niet meedoen: anders zou een
 * levende speler naar een toeschouwer in een muur of de lucht gehaald kunnen worden.
 */
final class TpaCommands implements TabExecutor, Listener {

    private static final class Request {
        final UUID from;
        final UUID to;
        final boolean here;
        BukkitTask expiry;

        Request(UUID from, UUID to, boolean here) {
            this.from = from;
            this.to = to;
            this.here = here;
        }
    }

    private final JavaPlugin plugin;
    private final Teleports teleports;
    private final int expireSeconds;
    private final int warmupSeconds;
    private final int cooldownSeconds;

    // Per aanvrager hooguit één verzoek; volgorde = oud -> nieuw.
    private final Map<UUID, Request> outgoing = new LinkedHashMap<>();
    private final Map<UUID, Long> lastTeleport = new HashMap<>();

    TpaCommands(JavaPlugin plugin, Teleports teleports) {
        this.plugin = plugin;
        this.teleports = teleports;
        expireSeconds = Math.max(10, plugin.getConfig().getInt("tpa.expire-seconds", 60));
        warmupSeconds = plugin.getConfig().getInt("tpa.warmup-seconds", 5);
        cooldownSeconds = plugin.getConfig().getInt("tpa.cooldown-seconds", 30);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player player = (Player) sender;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "tpa": request(player, args, false); break;
            case "tpahere": request(player, args, true); break;
            case "tpaccept": answer(player, args, true); break;
            case "tpdeny": answer(player, args, false); break;
            case "tpacancel": cancelOwn(player); break;
            default: return false;
        }
        return true;
    }

    // ---------- Verzoek sturen ----------

    private void request(Player player, String[] args, boolean here) {
        if (args.length != 1) {
            player.sendMessage(ChatColor.YELLOW + "Gebruik: /" + (here ? "tpahere" : "tpa") + " <speler>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            player.sendMessage(ChatColor.RED + "Speler '" + args[0] + "' is niet online.");
            return;
        }
        if (target.equals(player)) {
            player.sendMessage(ChatColor.RED + "Je kunt geen verzoek naar jezelf sturen.");
            return;
        }
        if (isSpectator(player)) {
            player.sendMessage(ChatColor.RED + "Als toeschouwer kun je geen /tpa gebruiken.");
            return;
        }
        if (isSpectator(target)) {
            player.sendMessage(ChatColor.RED + target.getName() + " is toeschouwer en kan niet meedoen met /tpa.");
            return;
        }
        long wait = cooldownLeft(player);
        if (wait > 0) {
            player.sendMessage(ChatColor.RED + "Je moet nog " + wait + " seconde(n) wachten voor je weer een verzoek kunt sturen.");
            return;
        }

        Request old = outgoing.remove(player.getUniqueId());
        if (old != null) old.expiry.cancel();

        Request req = new Request(player.getUniqueId(), target.getUniqueId(), here);
        req.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (outgoing.get(req.from) != req) return;
            outgoing.remove(req.from);
            player.sendMessage(ChatColor.GRAY + "Je verzoek aan " + target.getName() + " is verlopen.");
        }, expireSeconds * 20L);
        outgoing.put(player.getUniqueId(), req);

        player.sendMessage(ChatColor.GREEN + "Verzoek verstuurd naar " + ChatColor.WHITE + target.getName()
                + ChatColor.GREEN + ". Het verloopt over " + expireSeconds + " seconden. "
                + ChatColor.GRAY + "(/tpacancel om in te trekken)");

        target.sendMessage(SurvivalExtras.legacy(here
                ? "&e" + player.getName() + " &7vraagt of jij naar hen toe wilt teleporteren."
                : "&e" + player.getName() + " &7wil naar jou toe teleporteren."));
        target.sendMessage(Component.text()
                .append(button("[Accepteren]", NamedTextColor.GREEN, "/tpaccept " + player.getName(), "Klik om te accepteren"))
                .append(Component.text("  "))
                .append(button("[Weigeren]", NamedTextColor.RED, "/tpdeny " + player.getName(), "Klik om te weigeren"))
                .append(Component.text("  (verloopt over " + expireSeconds + " s)", NamedTextColor.DARK_GRAY))
                .build());
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.2f);
    }

    private static Component button(String text, NamedTextColor color, String command, String hover) {
        return Component.text(text, color, TextDecoration.BOLD)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    // ---------- Accepteren / weigeren ----------

    private void answer(Player player, String[] args, boolean accept) {
        Request req = findIncoming(player, args.length > 0 ? args[0] : null);
        if (req == null) {
            player.sendMessage(ChatColor.RED + (args.length > 0
                    ? "Je hebt geen openstaand verzoek van '" + args[0] + "'."
                    : "Je hebt geen openstaande teleport-verzoeken."));
            return;
        }
        outgoing.remove(req.from);
        req.expiry.cancel();

        Player requester = Bukkit.getPlayer(req.from);
        if (requester == null) {
            player.sendMessage(ChatColor.RED + "Die speler is niet meer online.");
            return;
        }

        if (!accept) {
            player.sendMessage(ChatColor.GRAY + "Je hebt het verzoek van " + requester.getName() + " geweigerd.");
            requester.sendMessage(ChatColor.RED + player.getName() + " heeft je verzoek geweigerd.");
            return;
        }

        Player mover = req.here ? player : requester;
        Player anchor = req.here ? requester : player;
        requester.sendMessage(ChatColor.GREEN + player.getName() + " heeft je verzoek geaccepteerd.");
        if (req.here) player.sendMessage(ChatColor.GREEN + "Je hebt het verzoek van " + requester.getName() + " geaccepteerd.");
        else player.sendMessage(ChatColor.GREEN + requester.getName() + " komt eraan.");

        teleports.teleport(mover, anchor.getName(), warmupSeconds, () -> destination(mover, anchor),
                () -> lastTeleport.put(req.from, System.currentTimeMillis()));
    }

    /** Pas aan het eind van de wachttijd: waar staat de ander nu, en mag het nog? */
    private static Location destination(Player mover, Player anchor) {
        if (!anchor.isOnline()) {
            mover.sendMessage(ChatColor.RED + anchor.getName() + " is offline gegaan, de teleport gaat niet door.");
            return null;
        }
        if (isSpectator(mover) || isSpectator(anchor)) {
            mover.sendMessage(ChatColor.RED + "De teleport gaat niet door: een van jullie is toeschouwer.");
            return null;
        }
        return anchor.getLocation();
    }

    /** Verzoek aan deze speler: van 'fromName', of anders het nieuwste. */
    private Request findIncoming(Player player, String fromName) {
        Request found = null;
        for (Request req : outgoing.values()) {
            if (!req.to.equals(player.getUniqueId())) continue;
            if (fromName != null) {
                Player from = Bukkit.getPlayer(req.from);
                if (from != null && from.getName().equalsIgnoreCase(fromName)) return req;
            } else {
                found = req;
            }
        }
        return found;
    }

    private void cancelOwn(Player player) {
        Request req = outgoing.remove(player.getUniqueId());
        if (req == null) {
            player.sendMessage(ChatColor.RED + "Je hebt geen openstaand verzoek.");
            return;
        }
        req.expiry.cancel();
        player.sendMessage(ChatColor.GRAY + "Je verzoek is ingetrokken.");
        Player target = Bukkit.getPlayer(req.to);
        if (target != null) target.sendMessage(ChatColor.GRAY + player.getName() + " heeft het teleport-verzoek ingetrokken.");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Iterator<Request> it = outgoing.values().iterator();
        while (it.hasNext()) {
            Request req = it.next();
            if (req.from.equals(uuid)) {
                req.expiry.cancel();
                it.remove();
            } else if (req.to.equals(uuid)) {
                req.expiry.cancel();
                it.remove();
                Player from = Bukkit.getPlayer(req.from);
                if (from != null) from.sendMessage(ChatColor.GRAY + event.getPlayer().getName() + " is uitgelogd, je verzoek is vervallen.");
            }
        }
    }

    // ---------- Hulpjes ----------

    private static boolean isSpectator(Player player) {
        return player.getGameMode() == GameMode.SPECTATOR;
    }

    private long cooldownLeft(Player player) {
        if (cooldownSeconds <= 0 || Teleports.bypass(player)) return 0;
        Long last = lastTeleport.get(player.getUniqueId());
        if (last == null) return 0;
        long left = cooldownSeconds - (System.currentTimeMillis() - last) / 1000L;
        return Math.max(0, left);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player) || args.length != 1) return Collections.emptyList();
        Player player = (Player) sender;
        String typed = args[0].toLowerCase(Locale.ROOT);
        List<String> names = new ArrayList<>();
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("tpa") || name.equals("tpahere")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.equals(player) && player.canSee(p) && p.getName().toLowerCase(Locale.ROOT).startsWith(typed)) names.add(p.getName());
            }
        } else if (name.equals("tpaccept") || name.equals("tpdeny")) {
            for (Request req : outgoing.values()) {
                if (!req.to.equals(player.getUniqueId())) continue;
                Player from = Bukkit.getPlayer(req.from);
                if (from != null && from.getName().toLowerCase(Locale.ROOT).startsWith(typed)) names.add(from.getName());
            }
        }
        return names;
    }
}
