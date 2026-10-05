package com.example.ooitnpcs;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

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
 * Speler-NPC's (Minecraft "mannequins") waar je op klikt om iets te doen,
 * bijvoorbeeld /survival uitvoeren of naar een wereld gaan.
 *
 * De NPC's staan in npcs.yml; het entity in de wereld draagt een label
 * (ooitnpcs:id). Raakt een NPC kwijt (bv. door /kill), dan maakt de plugin
 * hem vanzelf opnieuw zodra dat stuk wereld weer geladen wordt.
 */
public class OoitNpcs extends JavaPlugin implements TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    final Map<String, Npc> npcs = new LinkedHashMap<>();
    NamespacedKey idKey;
    private File file;

    @Override
    public void onEnable() {
        idKey = new NamespacedKey(this, "id");
        file = new File(getDataFolder(), "npcs.yml");
        loadFile();

        NpcListener listener = new NpcListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);
        Bukkit.getScheduler().runTaskTimer(this, listener::lookTick, 20L, 4L);
        Bukkit.getScheduler().runTaskTimer(this, listener::spinTick, 20L, 20L);

        getCommand("npc").setExecutor(this);
        getCommand("npc").setTabCompleter(this);

        // NPC's in stukken wereld die al geladen zijn (bv. bij de spawn) meteen controleren.
        Bukkit.getScheduler().runTask(this, () -> {
            for (Npc npc : npcs.values()) {
                Location loc = npc.location();
                if (loc != null && loc.isChunkLoaded() && loc.getChunk().isEntitiesLoaded()) {
                    listener.check(loc.getChunk(), Arrays.asList(loc.getChunk().getEntities()));
                }
            }
        });
        getLogger().info("OoitNPCs geladen: " + npcs.size() + " NPC('s).");
    }

    // ---------- Opslag ----------

    private void loadFile() {
        npcs.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("npcs");
        if (section == null) return;
        for (String id : section.getKeys(false)) {
            npcs.put(id, Npc.load(id, section.getConfigurationSection(id)));
        }
    }

    void saveFile() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(Arrays.asList(
                "OoitNPCs - pas dit liever aan met /npc in het spel.",
                "action: SPELER (commando als speler), CONSOLE (commando als console, {player} = naam),",
                "        WERELD (naar de spawn van een wereld) of GEEN."));
        for (Npc npc : npcs.values()) npc.save(yaml.createSection("npcs." + npc.id));
        try {
            yaml.save(file);
        } catch (IOException e) {
            getLogger().severe("Kon npcs.yml niet opslaan: " + e.getMessage());
        }
    }

    // ---------- Entity's ----------

    /** Het NPC-label van een entity, of null als het geen NPC van ons is. */
    String idOf(Entity entity) {
        return entity.getPersistentDataContainer().get(idKey, PersistentDataType.STRING);
    }

    /** Het hoofd-entity van deze NPC (mannequin of klikbox) als het nu geladen is, anders null. */
    Entity entityOf(Npc npc) {
        if (npc.entity == null) return null;
        Entity e = Bukkit.getEntity(npc.entity);
        return e != null && e.isValid() && npc.id.equals(idOf(e)) ? e : null;
    }

    private void tag(Entity entity, Npc npc) {
        entity.getPersistentDataContainer().set(idKey, PersistentDataType.STRING, npc.id);
        entity.setPersistent(true);
    }

    /** Alle delen van deze NPC die nu in de wereld staan weghalen. */
    private void removeEntities(Npc npc) {
        Entity main = entityOf(npc);
        if (main != null) main.remove();
        Location loc = npc.location();
        if (loc == null || !loc.isChunkLoaded()) return;
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, npc.size + 4, 3)) {
            if (npc.id.equals(idOf(e))) e.remove();
        }
    }

    /** NPC (opnieuw) neerzetten; wat er al stond gaat eerst weg. */
    Entity spawn(Npc npc) {
        removeEntities(npc);
        Location loc = npc.location();
        if (loc == null) return null;
        World world = loc.getWorld();
        Entity main;

        if (npc.kind == Npc.Kind.SPELER) {
            main = world.spawn(loc, Mannequin.class, m -> {
                tag(m, npc);
                m.setImmovable(true);
                m.setAI(false);
                m.setGravity(false);
                m.setInvulnerable(true);
                m.setSilent(true);
                m.setCollidable(false);
                m.setRemoveWhenFarAway(false);
                applyLooks(npc, m);
            });
        } else {
            float s = npc.size;
            // Onzichtbare klikbox, even groot als het voorwerp.
            main = world.spawn(loc, Interaction.class, i -> {
                tag(i, npc);
                i.setInteractionWidth(s);
                i.setInteractionHeight(s);
                i.setResponsive(true);
            });
            Location center = loc.clone().add(0, s / 2.0, 0);
            center.setYaw(0);
            center.setPitch(0);
            ItemDisplay display = world.spawn(center, ItemDisplay.class, d -> {
                tag(d, npc);
                d.setItemStack(npc.item);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(s, s, s), new AxisAngle4f()));
            });
            npc.display = display.getUniqueId();
            npc.spinAngle = 0;
            if (!npc.name.isEmpty() || npc.text != null) {
                Location top = loc.clone().add(0, s + 0.3, 0);
                world.spawn(top, TextDisplay.class, t -> {
                    tag(t, npc);
                    t.text(label(npc));
                    t.setBillboard(Display.Billboard.CENTER);
                    t.setAlignment(TextDisplay.TextAlignment.CENTER);
                    t.setShadowed(true);
                });
            }
        }
        npc.entity = main.getUniqueId();
        saveFile();
        return main;
    }

    /** Naam met de tekst eronder, voor boven een voorwerp-NPC. */
    private static Component label(Npc npc) {
        Component name = npc.name.isEmpty() ? Component.empty() : LEGACY.deserialize(npc.name);
        if (npc.text == null) return name;
        Component text = LEGACY.deserialize(npc.text);
        return npc.name.isEmpty() ? text : name.append(Component.newline()).append(text);
    }

    /** Naam, tekst en skin op een speler-NPC zetten. */
    void applyLooks(Npc npc, Mannequin m) {
        if (npc.name.isEmpty()) {
            m.customName(null);
            m.setCustomNameVisible(false);
        } else {
            m.customName(LEGACY.deserialize(npc.name));
            m.setCustomNameVisible(true);
        }
        m.setDescription(npc.text == null ? null : LEGACY.deserialize(npc.text));
        m.setProfile(npc.skin == null ? Mannequin.defaultProfile() : ResolvableProfile.resolvableProfile().name(npc.skin).build());
    }

    private void refresh(Npc npc) {
        Entity e = entityOf(npc);
        if (e instanceof Mannequin) {
            applyLooks(npc, (Mannequin) e);
            saveFile();
        } else if (npc.kind == Npc.Kind.ITEM && e != null) {
            spawn(npc); // voorwerp-NPC bestaat uit meerdere delen: gewoon opnieuw neerzetten
        } else {
            saveFile();
        }
    }

    // ---------- Klikken ----------

    void runAction(Npc npc, Player player) {
        String value = npc.actionValue;
        switch (npc.action) {
            case SPELER:
                player.performCommand(value);
                break;
            case CONSOLE:
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), value.replace("{player}", player.getName()));
                break;
            case WERELD:
                World world = Bukkit.getWorld(value);
                if (world == null) {
                    player.sendMessage(ChatColor.RED + "Die wereld is er nu niet. Probeer het later nog eens.");
                    return;
                }
                Location spawn = world.getSpawnLocation();
                player.teleportAsync(new Location(world, spawn.getBlockX() + 0.5, spawn.getY(), spawn.getBlockZ() + 0.5,
                        spawn.getYaw(), spawn.getPitch()));
                break;
            default:
                break;
        }
    }

    // ---------- Commando's ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "help";
        if (sub.equals("list")) {
            list(sender);
            return true;
        }
        if (sub.equals("create")) {
            create(sender, args);
            return true;
        }
        if (!Arrays.asList("remove", "info", "name", "text", "skin", "action", "move", "tp", "look", "respawn",
                "item", "size", "spin").contains(sub)) {
            help(sender);
            return true;
        }
        Npc npc = args.length > 1 ? npcs.get(args[1].toLowerCase(Locale.ROOT)) : null;
        if (npc == null) {
            sender.sendMessage(ChatColor.RED + (args.length > 1 ? "Geen NPC met de naam '" + args[1] + "'. Zie /npc list." : "Geef de naam van de NPC op. Zie /npc list."));
            return true;
        }
        String rest = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : "";

        boolean isItem = npc.kind == Npc.Kind.ITEM;
        if ((sub.equals("skin") || sub.equals("look")) && isItem) {
            sender.sendMessage(ChatColor.RED + "Dat kan alleen bij een speler-NPC. Voor een voorwerp: /npc item, size of spin.");
            return true;
        }
        if ((sub.equals("item") || sub.equals("size") || sub.equals("spin")) && !isItem) {
            sender.sendMessage(ChatColor.RED + "Dat kan alleen bij een voorwerp-NPC (gemaakt met /npc create <naam> item).");
            return true;
        }

        switch (sub) {
            case "remove": {
                removeEntities(npc);
                npcs.remove(npc.id);
                saveFile();
                sender.sendMessage(ChatColor.GREEN + "NPC '" + npc.id + "' verwijderd.");
                break;
            }
            case "info":
                info(sender, npc);
                break;
            case "name":
                npc.name = rest.equalsIgnoreCase("geen") ? "" : rest;
                refresh(npc);
                sender.sendMessage(ChatColor.GREEN + "Naam aangepast.");
                break;
            case "text":
                npc.text = rest.isEmpty() || rest.equalsIgnoreCase("geen") ? null : rest;
                refresh(npc);
                sender.sendMessage(ChatColor.GREEN + (npc.text == null ? "Tekst onder de naam weggehaald." : "Tekst onder de naam aangepast."));
                break;
            case "skin":
                if (rest.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc skin <naam> <minecraft-spelernaam|geen>");
                    return true;
                }
                if (!rest.equalsIgnoreCase("geen") && !rest.matches("[A-Za-z0-9_]{1,16}")) {
                    sender.sendMessage(ChatColor.RED + "Dat is geen geldige Minecraft-spelernaam.");
                    return true;
                }
                npc.skin = rest.equalsIgnoreCase("geen") ? null : rest;
                refresh(npc);
                sender.sendMessage(ChatColor.GREEN + "Skin aangepast" + (npc.skin == null ? "." : " naar die van " + npc.skin + " (kan even duren)."));
                break;
            case "action":
                action(sender, npc, args);
                break;
            case "move": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
                    return true;
                }
                removeEntities(npc); // op de oude plek weghalen
                npc.setLocation(((Player) sender).getLocation());
                spawn(npc);
                sender.sendMessage(ChatColor.GREEN + "NPC '" + npc.id + "' staat nu hier.");
                break;
            }
            case "tp": {
                if (!(sender instanceof Player)) {
                    sender.sendMessage("Alleen spelers kunnen dit commando gebruiken.");
                    return true;
                }
                Location loc = npc.location();
                if (loc == null) {
                    sender.sendMessage(ChatColor.RED + "De wereld van deze NPC is niet geladen.");
                    return true;
                }
                ((Player) sender).teleportAsync(loc.clone().add(loc.getDirection().multiply(2)).setDirection(loc.getDirection().multiply(-1)));
                break;
            }
            case "look": {
                Boolean on = rest.equalsIgnoreCase("aan") ? Boolean.TRUE : rest.equalsIgnoreCase("uit") ? Boolean.FALSE : null;
                if (on == null) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc look <naam> <aan|uit>");
                    return true;
                }
                npc.look = on;
                Entity m = entityOf(npc);
                if (m != null && !on) m.setRotation(npc.yaw, npc.pitch);
                saveFile();
                sender.sendMessage(ChatColor.GREEN + "Meekijken " + (on ? "aan" : "uit") + ".");
                break;
            }
            case "item": {
                ItemStack hand = sender instanceof Player ? ((Player) sender).getInventory().getItemInMainHand() : null;
                if (hand == null || hand.getType().isAir()) {
                    sender.sendMessage(ChatColor.RED + "Houd het nieuwe voorwerp in je hand.");
                    return true;
                }
                npc.item = hand.asOne();
                refresh(npc);
                sender.sendMessage(ChatColor.GREEN + "Voorwerp aangepast.");
                break;
            }
            case "size": {
                float size;
                try {
                    size = Float.parseFloat(rest.replace(',', '.'));
                } catch (NumberFormatException e) {
                    size = -1;
                }
                if (size < 0.25f || size > 6f) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc size <naam> <grootte>  (0.25 t/m 6, bv. 2 of 1.5)");
                    return true;
                }
                npc.size = size;
                refresh(npc);
                sender.sendMessage(ChatColor.GREEN + "Grootte is nu " + size + ".");
                break;
            }
            case "spin": {
                Boolean on = rest.equalsIgnoreCase("aan") ? Boolean.TRUE : rest.equalsIgnoreCase("uit") ? Boolean.FALSE : null;
                if (on == null) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc spin <naam> <aan|uit>");
                    return true;
                }
                npc.spin = on;
                refresh(npc); // opnieuw neerzetten = weer recht
                sender.sendMessage(ChatColor.GREEN + "Draaien " + (on ? "aan" : "uit") + ".");
                break;
            }
            case "respawn":
                if (npc.location() == null) {
                    sender.sendMessage(ChatColor.RED + "De wereld van deze NPC is niet geladen.");
                    return true;
                }
                spawn(npc);
                sender.sendMessage(ChatColor.GREEN + "NPC '" + npc.id + "' opnieuw neergezet.");
                break;
            default:
                break;
        }
        return true;
    }

    private static void help(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== OoitNPCs ===");
        line(sender, "/npc create <naam> [skin-speler]", "speler-NPC maken waar je staat");
        line(sender, "/npc create <naam> item", "zwevend voorwerp uit je hand (bv. wereldbol-hoofd)");
        line(sender, "/npc action <naam> speler <commando>", "klik = commando als speler, bv. survival");
        line(sender, "/npc action <naam> wereld <wereld>", "klik = naar de spawn van een wereld");
        line(sender, "/npc action <naam> console <commando>", "klik = console-commando, {player} = wie klikt");
        line(sender, "/npc action <naam> geen", "klik doet niets");
        line(sender, "/npc name <naam> <tekst>", "naam boven het hoofd (& voor kleuren, 'geen' = weg)");
        line(sender, "/npc text <naam> <tekst>", "tekst onder de naam ('geen' = weg)");
        line(sender, "/npc skin <naam> <speler>", "skin van een Minecraft-speler");
        line(sender, "/npc move <naam>", "NPC naar jouw plek verplaatsen");
        line(sender, "/npc look <naam> <aan|uit>", "speler-NPC: hoofd draaien naar spelers in de buurt");
        line(sender, "/npc item <naam>", "voorwerp-NPC: ander voorwerp (uit je hand)");
        line(sender, "/npc size <naam> <grootte>", "voorwerp-NPC: grootte in blokken (0.25 t/m 6)");
        line(sender, "/npc spin <naam> <aan|uit>", "voorwerp-NPC: langzaam ronddraaien");
        line(sender, "/npc list | info | tp | remove | respawn <naam>", "overzicht en beheer");
    }

    private static void line(CommandSender sender, String usage, String what) {
        sender.sendMessage(ChatColor.YELLOW + usage + ChatColor.GRAY + " - " + what);
    }

    private void list(CommandSender sender) {
        if (npcs.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "Er zijn nog geen NPC's. Maak er een met /npc create <naam>.");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + "=== NPC's ===");
        for (Npc npc : npcs.values()) {
            sender.sendMessage(ChatColor.YELLOW + npc.id + ChatColor.GRAY + " - " + npc.world + " "
                    + (int) Math.floor(npc.x) + ", " + (int) Math.floor(npc.y) + ", " + (int) Math.floor(npc.z)
                    + ChatColor.DARK_GRAY + " (" + describeAction(npc) + ")");
        }
    }

    private void info(CommandSender sender, Npc npc) {
        sender.sendMessage(ChatColor.GOLD + "=== NPC " + npc.id + " ===");
        sender.sendMessage(Component.text("Naam: ").color(net.kyori.adventure.text.format.NamedTextColor.GRAY)
                .append(npc.name.isEmpty() ? Component.text("(geen)") : LEGACY.deserialize(npc.name)));
        sender.sendMessage(ChatColor.GRAY + "Tekst: " + ChatColor.WHITE + (npc.text == null ? "(geen)" : npc.text));
        if (npc.kind == Npc.Kind.SPELER) {
            sender.sendMessage(ChatColor.GRAY + "Soort: " + ChatColor.WHITE + "speler" + ChatColor.GRAY + ", skin: "
                    + ChatColor.WHITE + (npc.skin == null ? "(standaard)" : npc.skin));
        } else {
            sender.sendMessage(ChatColor.GRAY + "Soort: " + ChatColor.WHITE + "voorwerp ("
                    + (npc.item == null ? "?" : npc.item.getType().getKey().getKey()) + ")" + ChatColor.GRAY
                    + ", grootte: " + ChatColor.WHITE + npc.size + ChatColor.GRAY + ", draaien: " + ChatColor.WHITE + (npc.spin ? "aan" : "uit"));
        }
        sender.sendMessage(ChatColor.GRAY + "Klik: " + ChatColor.WHITE + describeAction(npc));
        sender.sendMessage(ChatColor.GRAY + "Plek: " + ChatColor.WHITE + npc.world + " " + (int) Math.floor(npc.x) + ", "
                + (int) Math.floor(npc.y) + ", " + (int) Math.floor(npc.z));
        sender.sendMessage(ChatColor.GRAY + (npc.kind == Npc.Kind.SPELER ? "Meekijken: " + ChatColor.WHITE + (npc.look ? "aan" : "uit") + ChatColor.GRAY + ", n" : "N")
                + "u in de wereld: " + ChatColor.WHITE + (entityOf(npc) != null ? "ja" : "niet geladen"));
    }

    private static String describeAction(Npc npc) {
        switch (npc.action) {
            case SPELER: return "/" + npc.actionValue;
            case CONSOLE: return "console: " + npc.actionValue;
            case WERELD: return "naar wereld " + npc.actionValue;
            default: return "geen actie";
        }
    }

    private void create(CommandSender sender, String[] args) {
        if (args.length < 2 || args.length > 3) {
            sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc create <naam> [skin-speler]" + ChatColor.GRAY + "  - speler-NPC");
            sender.sendMessage(ChatColor.YELLOW + "         /npc create <naam> item" + ChatColor.GRAY + "  - zwevend voorwerp uit je hand");
            return;
        }
        boolean item = args.length == 3 && args[2].equalsIgnoreCase("item");
        // Vanuit de console (geen hand): een Eye of Ender.
        ItemStack hand = sender instanceof Player ? ((Player) sender).getInventory().getItemInMainHand() : new ItemStack(org.bukkit.Material.ENDER_EYE);
        if (item && (hand == null || hand.getType().isAir())) {
            sender.sendMessage(ChatColor.RED + "Houd het voorwerp (bv. een wereldbol-hoofd) in je hand.");
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_-]{1,24}")) {
            sender.sendMessage(ChatColor.RED + "Gebruik voor de naam alleen kleine letters, cijfers, - en _ (max. 24 tekens).");
            return;
        }
        if (npcs.containsKey(id)) {
            sender.sendMessage(ChatColor.RED + "Er bestaat al een NPC '" + id + "'.");
            return;
        }
        if (args.length == 3 && !item && !args[2].matches("[A-Za-z0-9_]{1,16}")) {
            sender.sendMessage(ChatColor.RED + "Dat is geen geldige Minecraft-spelernaam.");
            return;
        }

        // Vanuit de console: bij de spawn van de hoofdwereld.
        Location loc = sender instanceof Player
                ? ((Player) sender).getLocation()
                : Bukkit.getWorlds().get(0).getSpawnLocation().add(0.5, 0, 0.5);
        Npc npc = new Npc(id);
        npc.setLocation(loc);
        npc.name = "&e&l" + id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
        npc.text = "&7Klik op mij!";
        if (item) {
            npc.kind = Npc.Kind.ITEM;
            npc.item = hand.asOne();
        } else {
            npc.skin = args.length == 3 ? args[2] : null;
        }
        npcs.put(id, npc);
        if (spawn(npc) == null) {
            npcs.remove(id);
            sender.sendMessage(ChatColor.RED + "Kon de NPC niet neerzetten.");
            return;
        }
        sender.sendMessage(ChatColor.GREEN + "NPC '" + id + "' gemaakt. Stel nu in wat een klik doet, bv.: "
                + ChatColor.WHITE + "/npc action " + id + " speler survival");
    }

    private void action(CommandSender sender, Npc npc, String[] args) {
        String type = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "";
        String value = args.length > 3 ? String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim() : "";
        if (value.startsWith("/")) value = value.substring(1);

        switch (type) {
            case "speler":
            case "console":
                if (value.isEmpty()) {
                    sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc action " + npc.id + " " + type + " <commando>");
                    return;
                }
                npc.action = type.equals("speler") ? Npc.ActionType.SPELER : Npc.ActionType.CONSOLE;
                break;
            case "wereld":
                if (value.isEmpty() || Bukkit.getWorld(value) == null) {
                    sender.sendMessage(ChatColor.RED + "Geef een geladen wereld op (zie /world list).");
                    return;
                }
                npc.action = Npc.ActionType.WERELD;
                break;
            case "geen":
                npc.action = Npc.ActionType.GEEN;
                value = "";
                break;
            default:
                sender.sendMessage(ChatColor.YELLOW + "Gebruik: /npc action <naam> <speler|console|wereld|geen> [waarde]");
                sender.sendMessage(ChatColor.GRAY + "Bv. /npc action " + npc.id + " speler survival");
                return;
        }
        npc.actionValue = value;
        saveFile();
        sender.sendMessage(ChatColor.GREEN + "Klik op '" + npc.id + "' doet nu: " + describeAction(npc));
    }

    // ---------- Tab-aanvulling ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options;
        if (args.length == 1) {
            options = Arrays.asList("create", "list", "info", "action", "name", "text", "skin", "move", "look",
                    "item", "size", "spin", "tp", "remove", "respawn", "help");
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("create") && !args[0].equalsIgnoreCase("list")) {
            options = new ArrayList<>(npcs.keySet());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("action")) {
            options = Arrays.asList("speler", "wereld", "console", "geen");
        } else if (args.length == 4 && args[0].equalsIgnoreCase("action") && args[2].equalsIgnoreCase("wereld")) {
            options = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) options.add(w.getName());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("action") && args[2].equalsIgnoreCase("speler")) {
            options = Arrays.asList("survival", "lobby", "spawn");
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("look") || args[0].equalsIgnoreCase("spin"))) {
            options = Arrays.asList("aan", "uit");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("size")) {
            options = Arrays.asList("1", "1.5", "2", "3");
        } else if (args.length == 3 && (args[0].equalsIgnoreCase("skin") || args[0].equalsIgnoreCase("create"))) {
            options = new ArrayList<>();
            if (args[0].equalsIgnoreCase("create")) options.add("item");
            for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
        } else {
            return Collections.emptyList();
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(typed)) result.add(option);
        return result;
    }
}
