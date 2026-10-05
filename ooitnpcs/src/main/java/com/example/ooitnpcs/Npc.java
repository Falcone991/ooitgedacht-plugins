package com.example.ooitnpcs;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.util.Base64;
import java.util.UUID;

/** Eén NPC zoals hij in npcs.yml staat. */
final class Npc {

    /** Hoe de NPC eruitziet. */
    enum Kind {
        /** Speler-figuur met skin (Minecraft "mannequin"). */
        SPELER,
        /** Zwevend, draaiend voorwerp (bv. een wereldbol-hoofd), met een onzichtbare klikbox. */
        ITEM
    }

    /** Wat er gebeurt bij een klik. */
    enum ActionType {
        /** Commando uitvoeren alsof de speler het zelf typt, bv. "survival". */
        SPELER,
        /** Commando vanuit de console; {player} = naam van wie klikt. */
        CONSOLE,
        /** Naar de spawn van een wereld. */
        WERELD,
        /** Niets (alleen decoratie). */
        GEEN
    }

    final String id;
    Kind kind = Kind.SPELER;
    String world;
    double x, y, z;
    float yaw, pitch;
    /** Het hoofd-entity: de mannequin, of bij ITEM de klikbox (Interaction). */
    UUID entity;
    String name = "";
    String text = null;          // regel onder de naam; null = geen
    String skin = null;          // SPELER: spelernaam voor de skin; null = standaard
    boolean look = true;         // SPELER: hoofd draaien naar spelers in de buurt
    ItemStack item = null;       // ITEM: wat er zweeft
    float size = 1.5f;           // ITEM: grootte in blokken
    boolean spin = true;         // ITEM: langzaam ronddraaien
    ActionType action = ActionType.GEEN;
    String actionValue = "";

    // Alleen in het geheugen: het zwevende voorwerp (ITEM) en hoe ver het al gedraaid is.
    UUID display;
    float spinAngle;

    Npc(String id) {
        this.id = id;
    }

    Location location() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    void setLocation(Location loc) {
        world = loc.getWorld().getName();
        x = loc.getX();
        y = loc.getY();
        z = loc.getZ();
        yaw = loc.getYaw();
        pitch = 0;
    }

    /** Ligt deze NPC in het gegeven stuk wereld (chunk)? */
    boolean inChunk(World w, int chunkX, int chunkZ) {
        return w.getName().equals(world) && ((int) Math.floor(x) >> 4) == chunkX && ((int) Math.floor(z) >> 4) == chunkZ;
    }

    static Npc load(String id, ConfigurationSection s) {
        Npc npc = new Npc(id);
        try {
            npc.kind = Kind.valueOf(s.getString("kind", "SPELER"));
        } catch (IllegalArgumentException e) {
            npc.kind = Kind.SPELER;
        }
        npc.world = s.getString("world", "world");
        npc.x = s.getDouble("x");
        npc.y = s.getDouble("y");
        npc.z = s.getDouble("z");
        npc.yaw = (float) s.getDouble("yaw");
        npc.pitch = (float) s.getDouble("pitch");
        String uuid = s.getString("entity");
        if (uuid != null) {
            try {
                npc.entity = UUID.fromString(uuid);
            } catch (IllegalArgumentException ignored) {
                // wordt vanzelf opnieuw gemaakt
            }
        }
        npc.name = s.getString("name", "");
        npc.text = s.getString("text");
        npc.skin = s.getString("skin");
        npc.look = s.getBoolean("look", true);
        String item = s.getString("item");
        if (item != null) {
            try {
                npc.item = ItemStack.deserializeBytes(Base64.getDecoder().decode(item));
            } catch (RuntimeException ignored) {
                npc.item = null;
            }
        }
        npc.size = (float) s.getDouble("size", 1.5);
        npc.spin = s.getBoolean("spin", true);
        try {
            npc.action = ActionType.valueOf(s.getString("action", "GEEN"));
        } catch (IllegalArgumentException e) {
            npc.action = ActionType.GEEN;
        }
        npc.actionValue = s.getString("action-value", "");
        return npc;
    }

    void save(ConfigurationSection s) {
        s.set("kind", kind.name());
        s.set("world", world);
        s.set("x", x);
        s.set("y", y);
        s.set("z", z);
        s.set("yaw", (double) yaw);
        s.set("pitch", (double) pitch);
        s.set("entity", entity == null ? null : entity.toString());
        s.set("name", name);
        s.set("text", text);
        if (kind == Kind.SPELER) {
            s.set("skin", skin);
            s.set("look", look);
        } else {
            s.set("item", item == null ? null : Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            s.set("size", (double) size);
            s.set("spin", spin);
        }
        s.set("action", action.name());
        s.set("action-value", actionValue);
    }
}
