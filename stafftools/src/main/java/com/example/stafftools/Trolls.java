package com.example.stafftools;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Chicken;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Grapjes voor beheer, allemaal onschuldig (geen schade, niets kwijt):
 *  - /pumpkin <speler> [seconden]: pompoen op zijn hoofd (je kijkt door de gaatjes); zijn
 *    eigen helm wordt bewaard en daarna teruggezet, ook na uitloggen of een crash.
 *  - /chickenrain <speler> [aantal]: het regent kippen boven hem; na 30 s verdwijnen ze weer.
 *  - /scare <speler> [creeper|guardian|ghast|warden]: schrikgeluid (en beeld) alleen voor hem.
 */
final class Trolls implements TabExecutor, Listener {

    private static final String PERMISSION = "stafftools.staff";
    private static final List<String> SCARES = Arrays.asList("creeper", "guardian", "ghast", "warden");

    private final JavaPlugin plugin;
    private final NamespacedKey pumpkinKey;     // op de pompoen zelf
    private final NamespacedKey helmetKey;      // bewaarde helm, op de speler
    private final NamespacedKey pumpkinUntilKey;
    private final NamespacedKey chickenKey;
    private final Random random = new Random();

    Trolls(JavaPlugin plugin) {
        this.plugin = plugin;
        pumpkinKey = new NamespacedKey(plugin, "troll_pumpkin");
        helmetKey = new NamespacedKey(plugin, "troll_saved_helmet");
        pumpkinUntilKey = new NamespacedKey(plugin, "troll_pumpkin_until");
        chickenKey = new NamespacedKey(plugin, "troll_chicken");
        Bukkit.getScheduler().runTaskTimer(plugin, this::checkPumpkins, 20L, 20L);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(ChatColor.RED + "Je hebt geen rechten voor dit commando.");
            return true;
        }
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (args.length < 1) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: " + command.getUsage());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Speler '" + args[0] + "' is niet online.");
            return true;
        }
        switch (name) {
            case "pumpkin":
                pumpkin(sender, target, number(args, 30, 5, 600));
                break;
            case "chickenrain":
                chickenRain(sender, target, number(args, 20, 1, 50));
                break;
            case "scare":
                scare(sender, target, args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : SCARES.get(random.nextInt(SCARES.size())));
                break;
            default:
                return false;
        }
        plugin.getLogger().info("[Troll] " + sender.getName() + " /" + name + " " + String.join(" ", args));
        return true;
    }

    private static int number(String[] args, int def, int min, int max) {
        if (args.length < 2) return def;
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(args[1])));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ---------- /pumpkin ----------

    private void pumpkin(CommandSender sender, Player target, int seconds) {
        PersistentDataContainer pdc = target.getPersistentDataContainer();
        if (!pdc.has(pumpkinUntilKey, PersistentDataType.LONG)) {
            ItemStack helmet = target.getInventory().getHelmet();
            if (helmet != null && helmet.getType() != Material.AIR) {
                pdc.set(helmetKey, PersistentDataType.BYTE_ARRAY, helmet.serializeAsBytes());
            }
            ItemStack pumpkin = new ItemStack(Material.CARVED_PUMPKIN);
            ItemMeta meta = pumpkin.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "Boe!");
            meta.getPersistentDataContainer().set(pumpkinKey, PersistentDataType.BYTE, (byte) 1);
            pumpkin.setItemMeta(meta);
            target.getInventory().setHelmet(pumpkin);
        }
        pdc.set(pumpkinUntilKey, PersistentDataType.LONG, System.currentTimeMillis() + seconds * 1000L);
        target.playSound(target.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 1f, 1f);
        sender.sendMessage(ChatColor.GOLD + target.getName() + " heeft nu " + seconds + " s een pompoen op zijn hoofd.");
    }

    private void checkPumpkins() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Long until = p.getPersistentDataContainer().get(pumpkinUntilKey, PersistentDataType.LONG);
            if (until != null && now >= until) removePumpkin(p);
        }
    }

    /** Pompoen weg, eigen helm terug. */
    private void removePumpkin(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        ItemStack current = player.getInventory().getHelmet();
        if (isTrollPumpkin(current)) player.getInventory().setHelmet(null);
        byte[] saved = pdc.get(helmetKey, PersistentDataType.BYTE_ARRAY);
        if (saved != null) {
            ItemStack helmet = ItemStack.deserializeBytes(saved);
            if (player.getInventory().getHelmet() == null) player.getInventory().setHelmet(helmet);
            else player.getInventory().addItem(helmet).values()
                    .forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
        }
        pdc.remove(helmetKey);
        pdc.remove(pumpkinUntilKey);
    }

    private boolean isTrollPumpkin(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(pumpkinKey, PersistentDataType.BYTE);
    }

    /** De pompoen kan je niet zelf afzetten. */
    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (isTrollPumpkin(event.getCurrentItem()) || isTrollPumpkin(event.getCursor())) event.setCancelled(true);
    }

    /** Doodgaan met de pompoen op: de echte helm valt, niet de pompoen. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (!pdc.has(pumpkinUntilKey, PersistentDataType.LONG)) return;
        event.getDrops().removeIf(this::isTrollPumpkin);
        byte[] saved = pdc.get(helmetKey, PersistentDataType.BYTE_ARRAY);
        if (saved != null) {
            ItemStack helmet = ItemStack.deserializeBytes(saved);
            if (event.getKeepInventory()) player.getInventory().setHelmet(helmet);
            else event.getDrops().add(helmet);
        }
        if (event.getKeepInventory() && saved == null) player.getInventory().setHelmet(null);
        pdc.remove(helmetKey);
        pdc.remove(pumpkinUntilKey);
    }

    /** Uitgelogd met de pompoen op en de tijd is voorbij: meteen opruimen. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Long until = event.getPlayer().getPersistentDataContainer().get(pumpkinUntilKey, PersistentDataType.LONG);
        if (until != null && System.currentTimeMillis() >= until) removePumpkin(event.getPlayer());
    }

    // ---------- /chickenrain ----------

    private void chickenRain(CommandSender sender, Player target, int amount) {
        final int[] spawned = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!target.isOnline() || spawned[0] >= amount) {
                task.cancel();
                return;
            }
            for (int i = 0; i < 2 && spawned[0] < amount; i++, spawned[0]++) {
                Location at = target.getLocation().add(random.nextDouble() * 6 - 3, 8 + random.nextDouble() * 4, random.nextDouble() * 6 - 3);
                Chicken chicken = at.getWorld().spawn(at, Chicken.class, c -> {
                    c.setPersistent(false);
                    c.getPersistentDataContainer().set(chickenKey, PersistentDataType.BYTE, (byte) 1);
                });
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (chicken.isValid()) {
                        chicken.getWorld().spawnParticle(Particle.CLOUD, chicken.getLocation(), 5, 0.2, 0.2, 0.2, 0.02);
                        chicken.remove();
                    }
                }, 600L);
            }
            target.getWorld().playSound(target.getLocation(), Sound.ENTITY_CHICKEN_AMBIENT, 1f, 0.8f + random.nextFloat() * 0.6f);
        }, 0L, 3L);
        sender.sendMessage(ChatColor.GOLD + "Het regent " + amount + " kippen boven " + target.getName() + "!");
    }

    // ---------- /scare ----------

    private void scare(CommandSender sender, Player target, String kind) {
        Location behind = target.getLocation().subtract(target.getLocation().getDirection().setY(0).normalize().multiply(1.5));
        switch (kind) {
            case "creeper":
                target.playSound(behind, Sound.ENTITY_CREEPER_PRIMED, 1f, 0.5f);
                break;
            case "guardian":
                target.spawnParticle(Particle.ELDER_GUARDIAN, target.getLocation(), 1);
                target.playSound(target.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1f, 1f);
                break;
            case "ghast":
                target.playSound(behind, Sound.ENTITY_GHAST_SCREAM, 1f, 0.8f);
                break;
            case "warden":
                target.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 100, 0, false, false));
                target.playSound(behind, Sound.ENTITY_WARDEN_ROAR, 1f, 1f);
                target.playSound(target.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 1f);
                break;
            default:
                sender.sendMessage(ChatColor.YELLOW + "Kies: " + String.join(", ", SCARES));
                return;
        }
        sender.sendMessage(ChatColor.GOLD + target.getName() + " is laten schrikken (" + kind + ").");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(PERMISSION)) return Collections.emptyList();
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else if (args.length == 2) {
            switch (command.getName().toLowerCase(Locale.ROOT)) {
                case "scare": options.addAll(SCARES); break;
                case "pumpkin": options.addAll(Arrays.asList("10", "30", "60", "300")); break;
                case "chickenrain": options.addAll(Arrays.asList("10", "20", "50")); break;
                default: break;
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(typed)) result.add(option);
        return result;
    }
}
