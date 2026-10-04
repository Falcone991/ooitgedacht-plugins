package com.example.ooitworlds;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Eigen, simpele "Multiverse": extra werelden maken, laden en regels per
 * wereld (zie WorldSettings / WorldRules). Alles staat in worlds.yml en wordt
 * bij elke start vanzelf weer geladen.
 */
public class OoitWorlds extends JavaPlugin implements TabExecutor {

    private static final List<String> TYPES = Arrays.asList("void", "flat", "normal");
    private static final List<String> ENVIRONMENTS = Arrays.asList("normal", "nether", "end");

    private final Map<String, WorldSettings> worlds = new LinkedHashMap<>();
    private File file;
    private WorldRules rules;

    @Override
    public void onEnable() {
        file = new File(getDataFolder(), "worlds.yml");
        loadFile();

        rules = new WorldRules(this);
        Bukkit.getPluginManager().registerEvents(rules, this);
        Bukkit.getScheduler().runTaskTimer(this, rules::tick, 20L, 20L);

        getCommand("world").setExecutor(this);
        getCommand("world").setTabCompleter(this);

        int loaded = 0;
        for (Map.Entry<String, WorldSettings> entry : worlds.entrySet()) {
            if (!entry.getValue().autoload || Bukkit.getWorld(entry.getKey()) != null) continue;
            if (!exists(entry.getKey())) {
                getLogger().warning("Wereld '" + entry.getKey() + "' staat in worlds.yml, maar de map bestaat niet (meer). Overgeslagen.");
                continue;
            }
            if (load(entry.getKey(), entry.getValue()) != null) loaded++;
        }
        getLogger().info("OoitWorlds geladen: " + loaded + " extra wereld(en) geladen.");
    }

    WorldSettings settings(String worldName) {
        return worlds.get(worldName);
    }

    /** Spawn van een wereld, midden op het blok. */
    static Location spawn(World world) {
        Location l = world.getSpawnLocation();
        return new Location(world, l.getBlockX() + 0.5, l.getY(), l.getBlockZ() + 0.5, l.getYaw(), l.getPitch());
    }

    // ---------- Opslag ----------

    private void loadFile() {
        worlds.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("worlds");
        if (section == null) return;
        for (String name : section.getKeys(false)) {
            worlds.put(name, WorldSettings.load(section.getConfigurationSection(name)));
        }
    }

    private void saveFile() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(Arrays.asList(
                "OoitWorlds - werelden en hun regels.",
                "Pas dit liever aan met /world set <wereld> <instelling> <waarde> in het spel."));
        for (Map.Entry<String, WorldSettings> entry : worlds.entrySet()) {
            entry.getValue().save(yaml.createSection("worlds." + entry.getKey()));
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            getLogger().severe("Kon worlds.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Werelden laden ----------

    /*
     * Waar staan werelden? Sinds Minecraft 26 bewaart Paper extra werelden als
     * "dimensie" binnen de hoofdwereld: world/dimensions/minecraft/<naam>.
     * Een geüploade wereld (losse map naast 'world', uit een oude of nieuwe
     * Minecraft-versie) zet Paper bij het laden zelf daarheen over.
     */

    private static File dimensionFolder(String name) {
        File main = new File(Bukkit.getWorldContainer(), Bukkit.getWorlds().get(0).getName());
        return new File(main, "dimensions" + File.separator + "minecraft" + File.separator + name);
    }

    /** Staat er een geüploade wereldmap met deze naam naast 'world'? */
    private static boolean uploaded(String name) {
        File upload = new File(Bukkit.getWorldContainer(), name);
        File modern = new File(upload, "dimensions" + File.separator + "minecraft" + File.separator + "overworld");
        return new File(modern, "region").isDirectory() || new File(upload, "region").isDirectory();
    }

    /** Bestaat deze wereld al op de server, of is hij geüpload? */
    private static boolean exists(String name) {
        return dimensionFolder(name).isDirectory() || uploaded(name);
    }

    private World load(String name, WorldSettings s) {
        WorldCreator creator = new WorldCreator(name);
        switch (s.environment) {
            case "nether": creator.environment(World.Environment.NETHER); break;
            case "end": creator.environment(World.Environment.THE_END); break;
            default: creator.environment(World.Environment.NORMAL); break;
        }
        if (s.type.equals("void")) {
            creator.generator(new VoidGenerator());
            creator.generateStructures(false);
        } else if (s.type.equals("flat")) {
            creator.type(WorldType.FLAT);
            creator.generateStructures(false);
        }
        try {
            World world = creator.createWorld();
            if (world == null) getLogger().warning("Kon wereld '" + name + "' niet laden.");
            return world;
        } catch (RuntimeException e) {
            getLogger().severe("Fout bij het laden van wereld '" + name + "': " + e);
            return null;
        }
    }

    /** Klein platform onder de spawn van een lege wereld, anders val je meteen naar beneden. */
    private static void buildPlatform(World world) {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(x, 64, z).setType(x == 0 && z == 0 ? Material.GLOWSTONE : Material.SMOOTH_STONE);
            }
        }
        world.setSpawnLocation(0, 65, 0);
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        switch (sub) {
            case "list": list(sender); break;
            case "create": create(sender, args); break;
            case "import": importWorld(sender, args); break;
            case "load": loadCommand(sender, args); break;
            case "unload": unload(sender, args); break;
            case "tp": tp(sender, args); break;
            case "setspawn": setSpawn(sender); break;
            case "info": info(sender, args); break;
            case "set": set(sender, args); break;
            case "preset": preset(sender, args); break;
            default: help(sender); break;
        }
        return true;
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== OoitWorlds ===");
        line(sender, "/world list", "alle werelden");
        line(sender, "/world create <naam> <void|flat|normal> [normal|nether|end]", "nieuwe wereld maken");
        line(sender, "/world import <naam> [void|flat|normal]", "geüploade wereldmap laden");
        line(sender, "/world tp <naam> [speler]", "naar een wereld gaan");
        line(sender, "/world setspawn", "spawn van deze wereld = waar je staat");
        line(sender, "/world info [naam]", "regels van een wereld bekijken");
        line(sender, "/world set <naam> <instelling> <waarde>", "regel aanpassen");
        line(sender, "/world preset <naam> <lobby|normaal>", "alle regels in één keer");
        line(sender, "/world load|unload <naam>", "wereld aan/uit zetten (bestanden blijven)");
    }

    private static void line(CommandSender sender, String usage, String what) {
        sender.sendMessage(ChatColor.YELLOW + usage + ChatColor.GRAY + " - " + what);
    }

    private void list(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== Werelden ===");
        List<String> shown = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            shown.add(world.getName());
            WorldSettings s = worlds.get(world.getName());
            String extra = s != null && s.gamemode != null ? ", " + s.gamemode.name().toLowerCase(Locale.ROOT) : "";
            sender.sendMessage(ChatColor.GREEN + "● " + ChatColor.WHITE + world.getName() + ChatColor.GRAY
                    + " (" + world.getPlayers().size() + " speler(s)" + extra + ")");
        }
        for (String name : worlds.keySet()) {
            if (!shown.contains(name)) {
                sender.sendMessage(ChatColor.RED + "○ " + ChatColor.WHITE + name + ChatColor.GRAY + " (niet geladen)");
            }
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 3 || !TYPES.contains(args[2].toLowerCase(Locale.ROOT))
                || (args.length > 3 && !ENVIRONMENTS.contains(args[3].toLowerCase(Locale.ROOT)))) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world create <naam> <void|flat|normal> [normal|nether|end]");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!validName(sender, name)) return;
        if (Bukkit.getWorld(name) != null || exists(name)) {
            sender.sendMessage(ChatColor.RED + "Er bestaat al een wereld '" + name + "'. Gebruik /world import " + name + " om hem te laden.");
            return;
        }

        WorldSettings s = new WorldSettings();
        s.type = args[2].toLowerCase(Locale.ROOT);
        s.environment = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "normal";
        sender.sendMessage(ChatColor.GRAY + "Wereld '" + name + "' wordt gemaakt, even geduld...");
        World world = load(name, s);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "Maken is mislukt, kijk in de console.");
            return;
        }
        if (s.type.equals("void")) buildPlatform(world);
        worlds.put(name, s);
        saveFile();
        sender.sendMessage(ChatColor.GREEN + "Wereld '" + name + "' gemaakt (" + s.type + "). Ga erheen met /world tp " + name + ".");
    }

    private void importWorld(CommandSender sender, String[] args) {
        if (args.length < 2 || (args.length > 2 && !TYPES.contains(args[2].toLowerCase(Locale.ROOT)))) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world import <naam> [void|flat|normal]");
            sender.sendMessage(ChatColor.GRAY + "Het type bepaalt hoe NIEUWE stukken wereld eruitzien. Voor een lobby: void.");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!validName(sender, name)) return;
        if (Bukkit.getWorld(name) != null) {
            sender.sendMessage(ChatColor.RED + "Wereld '" + name + "' is al geladen.");
            return;
        }
        if (!exists(name)) {
            sender.sendMessage(ChatColor.RED + "Geen wereld '" + name + "' gevonden.");
            sender.sendMessage(ChatColor.GRAY + "Upload de wereldmap (met kleine letters als naam, bv. 'lobby') naar de hoofdmap"
                    + " van de server, naast de map 'world'. Daarin moet een map 'region' of 'dimensions' staan.");
            return;
        }
        if (!dimensionFolder(name).isDirectory()) {
            sender.sendMessage(ChatColor.GRAY + "Geüploade wereld '" + name + "' wordt overgezet en geladen, even geduld...");
        }
        WorldSettings s = worlds.getOrDefault(name, new WorldSettings());
        if (args.length > 2) s.type = args[2].toLowerCase(Locale.ROOT);
        s.autoload = true;
        World world = load(name, s);
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "Laden is mislukt, kijk in de console.");
            return;
        }
        worlds.put(name, s);
        saveFile();
        sender.sendMessage(ChatColor.GREEN + "Wereld '" + name + "' geladen en wordt voortaan automatisch geladen.");
    }

    private void loadCommand(CommandSender sender, String[] args) {
        if (args.length != 2 || !worlds.containsKey(args[1])) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world load <naam>  (alleen werelden uit /world list; anders /world import)");
            return;
        }
        String name = args[1];
        if (Bukkit.getWorld(name) != null) {
            sender.sendMessage(ChatColor.RED + "Wereld '" + name + "' is al geladen.");
            return;
        }
        WorldSettings s = worlds.get(name);
        if (load(name, s) == null) {
            sender.sendMessage(ChatColor.RED + "Laden is mislukt, kijk in de console.");
            return;
        }
        s.autoload = true;
        saveFile();
        sender.sendMessage(ChatColor.GREEN + "Wereld '" + name + "' geladen.");
    }

    private void unload(CommandSender sender, String[] args) {
        World world = args.length == 2 ? Bukkit.getWorld(args[1]) : null;
        if (world == null) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world unload <naam>  (een geladen wereld)");
            return;
        }
        World main = Bukkit.getWorlds().get(0);
        if (world.equals(main) || !worlds.containsKey(world.getName())) {
            sender.sendMessage(ChatColor.RED + "Alleen werelden die met /world create of /world import zijn toegevoegd kunnen uit.");
            return;
        }
        for (Player p : world.getPlayers()) {
            p.teleport(spawn(main));
            p.sendMessage(ChatColor.YELLOW + "Deze wereld wordt uitgezet, je bent naar de hoofdwereld gebracht.");
        }
        if (!Bukkit.unloadWorld(world, true)) {
            sender.sendMessage(ChatColor.RED + "Uitzetten is mislukt.");
            return;
        }
        worlds.get(world.getName()).autoload = false;
        saveFile();
        sender.sendMessage(ChatColor.GREEN + "Wereld '" + world.getName() + "' uitgezet. De bestanden blijven bewaard; aanzetten met /world load "
                + world.getName() + ".");
    }

    private void tp(CommandSender sender, String[] args) {
        World world = args.length >= 2 ? Bukkit.getWorld(args[1]) : null;
        if (world == null) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world tp <naam> [speler]  (een geladen wereld, zie /world list)");
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Speler '" + args[2] + "' is niet online.");
                return;
            }
        } else if (sender instanceof Player) {
            target = (Player) sender;
        } else {
            sender.sendMessage(ChatColor.RED + "Geef een speler op: /world tp <naam> <speler>");
            return;
        }
        target.teleportAsync(spawn(world)).thenAccept(ok -> {
            if (ok) target.sendMessage(ChatColor.GREEN + "Welkom in " + world.getName() + ".");
            else sender.sendMessage(ChatColor.RED + "Teleporteren is mislukt.");
        });
    }

    private void setSpawn(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
            return;
        }
        Player player = (Player) sender;
        Location loc = player.getLocation();
        player.getWorld().setSpawnLocation(loc);
        sender.sendMessage(ChatColor.GREEN + "Spawn van '" + player.getWorld().getName() + "' gezet op "
                + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ".");
    }

    private void info(CommandSender sender, String[] args) {
        String name = args.length >= 2 ? args[1]
                : sender instanceof Player ? ((Player) sender).getWorld().getName() : Bukkit.getWorlds().get(0).getName();
        WorldSettings s = worlds.getOrDefault(name, new WorldSettings());
        sender.sendMessage(ChatColor.GOLD + "=== " + name + " ===" + (Bukkit.getWorld(name) == null ? ChatColor.RED + " (niet geladen)" : ""));
        sender.sendMessage(ChatColor.GRAY + "type: " + ChatColor.WHITE + s.type + ChatColor.GRAY + ", omgeving: " + ChatColor.WHITE + s.environment);
        sender.sendMessage(ChatColor.GRAY + "gamemode: " + ChatColor.WHITE
                + (s.gamemode == null ? "geen (niet aanpassen)" : s.gamemode.name().toLowerCase(Locale.ROOT)));
        for (Map.Entry<String, String> flag : WorldSettings.FLAGS.entrySet()) {
            boolean on = s.flag(flag.getKey());
            sender.sendMessage((on ? ChatColor.GREEN + "aan " : ChatColor.RED + "uit ") + ChatColor.WHITE + flag.getKey()
                    + ChatColor.GRAY + " - " + flag.getValue());
        }
    }

    private void set(CommandSender sender, String[] args) {
        if (args.length != 4) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world set <naam> <instelling> <waarde>");
            sender.sendMessage(ChatColor.GRAY + "Instellingen: gamemode (survival/creative/adventure/geen), "
                    + String.join(", ", WorldSettings.FLAGS.keySet()) + " (aan/uit)");
            return;
        }
        String name = args[1];
        if (Bukkit.getWorld(name) == null && !worlds.containsKey(name)) {
            sender.sendMessage(ChatColor.RED + "Wereld '" + name + "' bestaat niet. Zie /world list.");
            return;
        }
        String key = args[2].toLowerCase(Locale.ROOT);
        String value = args[3].toLowerCase(Locale.ROOT);
        WorldSettings s = worlds.computeIfAbsent(name, n -> new WorldSettings());

        if (key.equals("gamemode")) {
            GameMode mode = WorldSettings.parseGameMode(value);
            if (mode == null && !value.equals("geen")) {
                sender.sendMessage(ChatColor.RED + "Kies survival, creative, adventure of geen.");
                return;
            }
            s.gamemode = mode;
        } else if (WorldSettings.FLAGS.containsKey(key)) {
            Boolean on = parseOnOff(value);
            if (on == null) {
                sender.sendMessage(ChatColor.RED + "Kies aan of uit.");
                return;
            }
            s.setFlag(key, on);
        } else {
            sender.sendMessage(ChatColor.RED + "Onbekende instelling '" + key + "'. Zie /world set.");
            return;
        }
        saveFile();
        applyToPlayers(name);
        sender.sendMessage(ChatColor.GREEN + name + ": " + key + " = " + value);
    }

    private void preset(CommandSender sender, String[] args) {
        if (args.length != 3 || !(args[2].equalsIgnoreCase("lobby") || args[2].equalsIgnoreCase("normaal"))) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /world preset <naam> <lobby|normaal>");
            sender.sendMessage(ChatColor.GRAY + "lobby = adventure, geen schade/honger/pvp/mobs/bouwen/portalen, altijd dag, geen regen, void-terug.");
            sender.sendMessage(ChatColor.GRAY + "normaal = alles weer zoals in vanilla.");
            return;
        }
        String name = args[1];
        if (Bukkit.getWorld(name) == null && !worlds.containsKey(name)) {
            sender.sendMessage(ChatColor.RED + "Wereld '" + name + "' bestaat niet. Zie /world list.");
            return;
        }
        WorldSettings s = worlds.computeIfAbsent(name, n -> new WorldSettings());
        if (args[2].equalsIgnoreCase("lobby")) s.applyLobbyPreset();
        else s.applyNormalPreset();
        saveFile();
        applyToPlayers(name);
        sender.sendMessage(ChatColor.GREEN + "Preset '" + args[2].toLowerCase(Locale.ROOT) + "' toegepast op " + name + ". Bekijk met /world info " + name + ".");
    }

    private void applyToPlayers(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        for (Player p : world.getPlayers()) rules.applyGameMode(p);
    }

    private static Boolean parseOnOff(String value) {
        switch (value) {
            case "aan": case "on": case "true": case "ja": return true;
            case "uit": case "off": case "false": case "nee": return false;
            default: return null;
        }
    }

    private static boolean validName(CommandSender sender, String name) {
        if (name.matches("[a-z0-9_-]{1,32}")) return true;
        sender.sendMessage(ChatColor.RED + "Gebruik voor de naam alleen kleine letters, cijfers, - en _ (max. 32 tekens).");
        return false;
    }

    // ---------- Tab-aanvulling ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options;
        if (args.length == 1) {
            options = Arrays.asList("list", "create", "import", "load", "unload", "tp", "setspawn", "info", "set", "preset", "help");
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (args.length == 2 && !sub.equals("create") && !sub.equals("import")) {
                options = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) options.add(w.getName());
                for (String name : worlds.keySet()) if (!options.contains(name)) options.add(name);
            } else if (args.length == 3 && (sub.equals("create") || sub.equals("import"))) {
                options = TYPES;
            } else if (args.length == 4 && sub.equals("create")) {
                options = ENVIRONMENTS;
            } else if (args.length == 3 && sub.equals("tp")) {
                options = new ArrayList<>();
                for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
            } else if (args.length == 3 && sub.equals("set")) {
                options = new ArrayList<>(WorldSettings.FLAGS.keySet());
                options.add(0, "gamemode");
            } else if (args.length == 4 && sub.equals("set")) {
                options = args[2].equalsIgnoreCase("gamemode")
                        ? Arrays.asList("survival", "creative", "adventure", "geen")
                        : Arrays.asList("aan", "uit");
            } else if (args.length == 3 && sub.equals("preset")) {
                options = Arrays.asList("lobby", "normaal");
            } else {
                return Collections.emptyList();
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(typed)) result.add(option);
        return result;
    }
}
