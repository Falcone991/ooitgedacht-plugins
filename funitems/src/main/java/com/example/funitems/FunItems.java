package com.example.funitems;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public class FunItems extends JavaPlugin implements Listener {

    private enum RpsChoice {
        STEEN, PAPIER, SCHAAR;

        /** true als 'this' wint van 'other'. */
        boolean wint(RpsChoice other) {
            return (this == STEEN && other == SCHAAR)
                    || (this == PAPIER && other == STEEN)
                    || (this == SCHAAR && other == PAPIER);
        }
    }

    private final Random random = new Random();

    // Cooldowns per speler per item, zodat je niet kan spammen (vooral belangrijk voor de gok-nugget)
    private final Map<UUID, Long> goldCooldown = new HashMap<>();
    private final Map<UUID, Long> copperCooldown = new HashMap<>();
    private final Map<UUID, Long> ironCooldown = new HashMap<>();

    // Config: goud (kop of munt)
    private boolean goldEnabled;
    private int goldCooldownSeconds;
    private String goldHeadsMessage;
    private String goldTailsMessage;
    private Sound goldSound;
    private float goldSoundVolume;
    private float goldSoundPitch;

    // Config: koper (steen/papier/schaar)
    private boolean copperEnabled;
    private int copperCooldownSeconds;
    private String copperWinMessage;
    private String copperLoseMessage;
    private String copperDrawMessage;
    private Sound copperWinSound;
    private Sound copperLoseSound;
    private Sound copperDrawSound;

    // Config: ijzer (1 op 10 gok)
    private boolean ironEnabled;
    private int ironCooldownSeconds;
    private int ironWinChanceOutOf;
    private String ironWinMessage;
    private String ironLoseMessage;
    private int ironWinXpReward;
    private Sound ironWinSound;
    private Particle ironWinParticle;
    private int ironWinParticleCount;
    private Sound ironLoseSound;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        getLogger().info("FunItems geladen.");
    }

    private void loadSettings() {
        FileConfiguration c = getConfig();

        goldEnabled = c.getBoolean("gold-coinflip.enabled", true);
        goldCooldownSeconds = c.getInt("gold-coinflip.cooldown-seconds", 1);
        goldHeadsMessage = c.getString("gold-coinflip.heads-message", "&6&lKop!");
        goldTailsMessage = c.getString("gold-coinflip.tails-message", "&6&lMunt!");
        goldSound = parseSound(c.getString("gold-coinflip.sound", "ENTITY_EXPERIENCE_ORB_PICKUP"), Sound.ENTITY_EXPERIENCE_ORB_PICKUP);
        goldSoundVolume = (float) c.getDouble("gold-coinflip.sound-volume", 0.5);
        goldSoundPitch = (float) c.getDouble("gold-coinflip.sound-pitch", 1.5);

        copperEnabled = c.getBoolean("copper-rps.enabled", true);
        copperCooldownSeconds = c.getInt("copper-rps.cooldown-seconds", 1);
        copperWinMessage = c.getString("copper-rps.win-message", "&aGewonnen!");
        copperLoseMessage = c.getString("copper-rps.lose-message", "&cVerloren!");
        copperDrawMessage = c.getString("copper-rps.draw-message", "&eGelijkspel!");
        copperWinSound = parseSound(c.getString("copper-rps.win-sound", "ENTITY_PLAYER_LEVELUP"), Sound.ENTITY_PLAYER_LEVELUP);
        copperLoseSound = parseSound(c.getString("copper-rps.lose-sound", "ENTITY_VILLAGER_NO"), Sound.ENTITY_VILLAGER_NO);
        copperDrawSound = parseSound(c.getString("copper-rps.draw-sound", "UI_BUTTON_CLICK"), Sound.UI_BUTTON_CLICK);

        ironEnabled = c.getBoolean("iron-gamble.enabled", true);
        ironCooldownSeconds = c.getInt("iron-gamble.cooldown-seconds", 3);
        ironWinChanceOutOf = Math.max(1, c.getInt("iron-gamble.win-chance-out-of", 10));
        ironWinMessage = c.getString("iron-gamble.win-message", "&a&lJACKPOT!");
        ironLoseMessage = c.getString("iron-gamble.lose-message", "&7Helaas, geen geluk.");
        ironWinXpReward = c.getInt("iron-gamble.win-xp-reward", 50);
        ironWinSound = parseSound(c.getString("iron-gamble.win-sound", "UI_TOAST_CHALLENGE_COMPLETE"), Sound.UI_TOAST_CHALLENGE_COMPLETE);
        ironWinParticle = parseParticle(c.getString("iron-gamble.win-particle", "FIREWORK"), Particle.FIREWORK);
        ironWinParticleCount = c.getInt("iron-gamble.win-particle-count", 40);
        ironLoseSound = parseSound(c.getString("iron-gamble.lose-sound", "ENTITY_VILLAGER_NO"), Sound.ENTITY_VILLAGER_NO);
    }

    private Sound parseSound(String name, Sound fallback) {
        try {
            return Sound.valueOf(name);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Onbekend geluid '" + name + "' in config.yml, val terug op " + fallback + ".");
            return fallback;
        }
    }

    private Particle parseParticle(String name, Particle fallback) {
        try {
            return Particle.valueOf(name);
        } catch (IllegalArgumentException e) {
            getLogger().warning("Onbekend deeltje '" + name + "' in config.yml, val terug op " + fallback + ".");
            return fallback;
        }
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Voorkom dat dit twee keer afgaat (hoofd- en secundaire hand).
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("funitems.use")) return;

        Material item = player.getInventory().getItemInMainHand().getType();

        if (item == Material.GOLD_NUGGET) {
            if (goldEnabled) playCoinFlip(player);
        } else if (item == Material.COPPER_NUGGET) {
            if (copperEnabled) playRockPaperScissors(player);
        } else if (item == Material.IRON_NUGGET) {
            if (ironEnabled) playIronGamble(player);
        }
    }

    // ---------- Goud: kop of munt ----------

    private void playCoinFlip(Player player) {
        if (onCooldown(goldCooldown, player, goldCooldownSeconds)) return;

        boolean heads = random.nextBoolean();
        String message = heads ? goldHeadsMessage : goldTailsMessage;

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        player.playSound(player.getLocation(), goldSound, goldSoundVolume, goldSoundPitch);
    }

    // ---------- Koper: steen, papier, schaar ----------

    private void playRockPaperScissors(Player player) {
        if (onCooldown(copperCooldown, player, copperCooldownSeconds)) return;

        RpsChoice[] choices = RpsChoice.values();
        RpsChoice yours = choices[random.nextInt(choices.length)];
        RpsChoice opponent = choices[random.nextInt(choices.length)];

        String template;
        Sound sound;
        if (yours == opponent) {
            template = copperDrawMessage;
            sound = copperDrawSound;
        } else if (yours.wint(opponent)) {
            template = copperWinMessage;
            sound = copperWinSound;
        } else {
            template = copperLoseMessage;
            sound = copperLoseSound;
        }

        String message = template
                .replace("{you}", prettyRps(yours))
                .replace("{opponent}", prettyRps(opponent));

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
    }

    private String prettyRps(RpsChoice choice) {
        switch (choice) {
            case STEEN: return "Steen";
            case PAPIER: return "Papier";
            default: return "Schaar";
        }
    }

    // ---------- IJzer: 1 op 10 gok ----------

    private void playIronGamble(Player player) {
        if (onCooldown(ironCooldown, player, ironCooldownSeconds)) return;

        boolean win = random.nextInt(ironWinChanceOutOf) == 0;

        if (win) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', ironWinMessage));
            player.playSound(player.getLocation(), ironWinSound, 1.0f, 1.0f);
            player.getWorld().spawnParticle(ironWinParticle, player.getLocation().add(0, 1, 0), ironWinParticleCount, 0.5, 0.5, 0.5, 0.05);
            if (ironWinXpReward > 0) {
                player.giveExp(ironWinXpReward);
            }
        } else {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', ironLoseMessage));
            player.playSound(player.getLocation(), ironLoseSound, 1.0f, 1.0f);
        }
    }

    // ---------- Cooldown-helper ----------

    private boolean onCooldown(Map<UUID, Long> cooldownMap, Player player, int cooldownSeconds) {
        if (cooldownSeconds <= 0) return false;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = cooldownMap.get(uuid);

        if (last != null && (now - last) < cooldownSeconds * 1000L) {
            return true;
        }
        cooldownMap.put(uuid, now);
        return false;
    }
}
