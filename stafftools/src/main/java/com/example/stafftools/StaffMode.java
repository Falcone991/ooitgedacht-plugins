package com.example.stafftools;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Meekijken als beheer:
 *  - /staffmode (/sm): onzichtbaar meekijken als toeschouwer. Je plek en gamemode worden
 *    bewaard (op de speler zelf, dus ook na een crash/herstart) en bij uitzetten teruggezet.
 *  - /spectate <speler> (/spec): staffmode aan en meteen naar die speler.
 *  - /invsee <speler>: inventory van een online speler openen (en aanpassen).
 *  - /endersee <speler>: zijn enderkist openen.
 *
 * In staffmode ben je ook uit de tab-lijst weg voor gewone spelers; beheer ziet je wel.
 */
final class StaffMode implements TabExecutor, Listener {

    private static final String PERMISSION = "stafftools.staff";

    private final JavaPlugin plugin;
    private final NamespacedKey activeKey;
    private final NamespacedKey locationKey;
    private final NamespacedKey gameModeKey;

    StaffMode(JavaPlugin plugin) {
        this.plugin = plugin;
        activeKey = new NamespacedKey(plugin, "staffmode");
        locationKey = new NamespacedKey(plugin, "staffmode_location");
        gameModeKey = new NamespacedKey(plugin, "staffmode_gamemode");
    }

    boolean isActive(Player player) {
        return player.getPersistentDataContainer().has(activeKey, PersistentDataType.BYTE);
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return true;
        }
        Player staff = (Player) sender;
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "staffmode":
                if (isActive(staff)) disable(staff);
                else enable(staff);
                return true;
            case "spectate": {
                Player target = target(staff, args, "/spectate <speler>");
                if (target == null) return true;
                if (!isActive(staff)) enable(staff);
                staff.teleport(target.getLocation());
                staff.sendMessage(ChatColor.AQUA + "Je kijkt mee bij " + target.getName() + "."
                        + ChatColor.GRAY + " Klik op hem om door zijn ogen te kijken (shift = los). Terug: /staffmode");
                return true;
            }
            case "invsee": {
                Player target = target(staff, args, "/invsee <speler>");
                if (target == null) return true;
                staff.openInventory(target.getInventory());
                plugin.getLogger().info("[Invsee] " + staff.getName() + " opent de inventory van " + target.getName() + ".");
                return true;
            }
            case "endersee": {
                Player target = target(staff, args, "/endersee <speler>");
                if (target == null) return true;
                staff.openInventory(target.getEnderChest());
                plugin.getLogger().info("[Invsee] " + staff.getName() + " opent de enderkist van " + target.getName() + ".");
                return true;
            }
            default:
                return false;
        }
    }

    private Player target(Player staff, String[] args, String usage) {
        if (args.length != 1) {
            staff.sendMessage(ChatColor.YELLOW + "Gebruik: " + usage);
            return null;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            staff.sendMessage(ChatColor.RED + "Speler '" + args[0] + "' is niet online.");
            return null;
        }
        if (target.equals(staff)) {
            staff.sendMessage(ChatColor.RED + "Dat ben je zelf.");
            return null;
        }
        return target;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION) || args.length != 1 || command.getName().equalsIgnoreCase("staffmode")) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(p.getName());
        }
        return result;
    }

    // ---------- Aan / uit ----------

    private void enable(Player staff) {
        PersistentDataContainer pdc = staff.getPersistentDataContainer();
        Location loc = staff.getLocation();
        pdc.set(locationKey, PersistentDataType.STRING, loc.getWorld().getName() + ";" + loc.getX() + ";" + loc.getY()
                + ";" + loc.getZ() + ";" + loc.getYaw() + ";" + loc.getPitch());
        pdc.set(gameModeKey, PersistentDataType.STRING, staff.getGameMode().name());
        pdc.set(activeKey, PersistentDataType.BYTE, (byte) 1);
        staff.setGameMode(GameMode.SPECTATOR);
        hideFromPlayers(staff);
        staff.sendMessage(ChatColor.AQUA + "Staffmode aan: je bent onzichtbaar en kijkt mee als toeschouwer."
                + ChatColor.GRAY + " Terug naar waar je was: /staffmode");
        plugin.getLogger().info("[Staffmode] " + staff.getName() + " zet staffmode aan.");
    }

    private void disable(Player staff) {
        PersistentDataContainer pdc = staff.getPersistentDataContainer();
        Location back = parseLocation(pdc.get(locationKey, PersistentDataType.STRING));
        GameMode mode = GameMode.SURVIVAL;
        try {
            mode = GameMode.valueOf(pdc.getOrDefault(gameModeKey, PersistentDataType.STRING, "SURVIVAL"));
        } catch (IllegalArgumentException ignored) {
            // standaard survival
        }
        pdc.remove(activeKey);
        pdc.remove(locationKey);
        pdc.remove(gameModeKey);
        staff.setSpectatorTarget(null);
        if (back != null) staff.teleport(back);
        staff.setGameMode(mode);
        for (Player other : Bukkit.getOnlinePlayers()) other.showPlayer(plugin, staff);
        staff.sendMessage(ChatColor.YELLOW + "Staffmode uit: je staat weer waar je was.");
        plugin.getLogger().info("[Staffmode] " + staff.getName() + " zet staffmode uit.");
    }

    private void hideFromPlayers(Player staff) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(staff) && !other.hasPermission(PERMISSION)) other.hidePlayer(plugin, staff);
        }
    }

    private static Location parseLocation(String text) {
        if (text == null) return null;
        String[] parts = text.split(";");
        if (parts.length != 6) return null;
        World world = Bukkit.getWorld(parts[0]);
        if (world == null) return null;
        try {
            return new Location(world, Double.parseDouble(parts[1]), Double.parseDouble(parts[2]), Double.parseDouble(parts[3]),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---------- Onzichtbaar blijven ----------

    /** Nieuwe speler online: beheer in staffmode voor hem verbergen; zelf nog in staffmode: weer verbergen. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        if (!joined.hasPermission(PERMISSION)) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (isActive(staff)) joined.hidePlayer(plugin, staff);
            }
        }
        if (isActive(joined)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!joined.isOnline() || !isActive(joined)) return;
                if (!joined.hasPermission(PERMISSION)) {
                    disable(joined); // geen beheer meer
                    return;
                }
                joined.setGameMode(GameMode.SPECTATOR); // bv. de lobby zette iets anders
                hideFromPlayers(joined);
                joined.sendMessage(ChatColor.AQUA + "Je staat nog in staffmode." + ChatColor.GRAY + " Uitzetten: /staffmode");
            }, 10L);
        }
    }
}
