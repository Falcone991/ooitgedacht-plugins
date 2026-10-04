package com.example.survivalextras;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Drie losse onderdelen in één plugin:
 *  - SleepSkip: nacht overslaan als een deel van de spelers slaapt, met titels en geluidjes;
 *  - TpaCommands: /tpa en /tpahere met verzoek, wachttijd en cooldown;
 *  - HomeCommands: maximaal 2 homes met wachttijd en cooldown.
 * Teleports regelt de gedeelde "stil blijven staan"-wachttijd.
 */
public class SurvivalExtras extends JavaPlugin {

    private HomeCommands homes;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        FileConfiguration c = getConfig();

        Teleports teleports = new Teleports(this);
        Bukkit.getPluginManager().registerEvents(teleports, this);

        if (c.getBoolean("sleep.enabled", true)) {
            Bukkit.getPluginManager().registerEvents(new SleepSkip(this), this);
        }

        String[] tpaCommands = {"tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel"};
        if (c.getBoolean("tpa.enabled", true)) {
            TpaCommands tpa = new TpaCommands(this, teleports);
            Bukkit.getPluginManager().registerEvents(tpa, this);
            register(tpa, tpaCommands);
        } else {
            disable(tpaCommands);
        }

        String[] homeCommands = {"sethome", "home", "delhome", "homes"};
        if (c.getBoolean("homes.enabled", true)) {
            homes = new HomeCommands(this, teleports);
            register(homes, homeCommands);
        } else {
            disable(homeCommands);
        }

        getLogger().info("SurvivalExtras geladen. Slapen: " + c.getBoolean("sleep.enabled", true)
                + " (" + c.getInt("sleep.percentage", 50) + "%), tpa: " + c.getBoolean("tpa.enabled", true)
                + ", homes: " + c.getBoolean("homes.enabled", true) + " (max " + c.getInt("homes.max-homes", 2) + ").");
    }

    @Override
    public void onDisable() {
        if (homes != null) homes.save();
    }

    private void register(TabExecutor executor, String... names) {
        for (String name : names) {
            PluginCommand command = getCommand(name);
            if (command == null) continue;
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    private void disable(String... names) {
        for (String name : names) {
            PluginCommand command = getCommand(name);
            if (command == null) continue;
            command.setExecutor((sender, cmd, label, args) -> {
                sender.sendMessage(ChatColor.RED + "Dit staat uit op deze server.");
                return true;
            });
        }
    }

    /** Tekst met &-kleurcodes naar een Adventure-component. */
    static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
