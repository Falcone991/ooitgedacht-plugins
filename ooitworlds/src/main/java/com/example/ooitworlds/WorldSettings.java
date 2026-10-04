package com.example.ooitworlds;

import org.bukkit.GameMode;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Regels van één wereld. Standaard = gewoon vanilla-gedrag. */
final class WorldSettings {

    /** Aan/uit-regels, met uitleg voor /world info en /world help. Volgorde = volgorde in de lijst. */
    static final Map<String, String> FLAGS = new LinkedHashMap<>();

    static {
        FLAGS.put("pvp", "spelers kunnen elkaar schade doen");
        FLAGS.put("schade", "spelers kunnen schade krijgen");
        FLAGS.put("honger", "de hongerbalk loopt leeg");
        FLAGS.put("mobs", "vijandige en natuurlijke mobs spawnen");
        FLAGS.put("bouwen", "spelers (geen OP) kunnen bouwen en slopen");
        FLAGS.put("portalen", "Nether- en End-portalen werken");
        FLAGS.put("altijd-dag", "het is altijd middag");
        FLAGS.put("geen-regen", "het regent of onweert nooit");
        FLAGS.put("void-terug", "wie in de void valt, komt terug op de spawn");
    }

    /** Wat de flags zijn als er niets is ingesteld (= vanilla). */
    private static boolean defaultFlag(String flag) {
        switch (flag) {
            case "altijd-dag":
            case "geen-regen":
            case "void-terug":
                return false;
            default:
                return true;
        }
    }

    String type = "normal";           // normal / flat / void (alleen bij aanmaken en laden van nieuwe chunks)
    String environment = "normal";    // normal / nether / end
    boolean autoload = true;
    GameMode gamemode = null;         // null = niet aanpassen
    private final Map<String, Boolean> flags = new LinkedHashMap<>();

    boolean flag(String name) {
        Boolean value = flags.get(name);
        return value != null ? value : defaultFlag(name);
    }

    void setFlag(String name, boolean value) {
        flags.put(name, value);
    }

    /** Alles in één keer goed zetten voor een lobby. */
    void applyLobbyPreset() {
        gamemode = GameMode.ADVENTURE;
        setFlag("pvp", false);
        setFlag("schade", false);
        setFlag("honger", false);
        setFlag("mobs", false);
        setFlag("bouwen", false);
        setFlag("portalen", false);
        setFlag("altijd-dag", true);
        setFlag("geen-regen", true);
        setFlag("void-terug", true);
    }

    void applyNormalPreset() {
        gamemode = null;
        flags.clear();
    }

    static WorldSettings load(ConfigurationSection s) {
        WorldSettings w = new WorldSettings();
        if (s == null) return w;
        w.type = s.getString("type", "normal");
        w.environment = s.getString("environment", "normal");
        w.autoload = s.getBoolean("autoload", true);
        w.gamemode = parseGameMode(s.getString("gamemode", "geen"));
        for (String flag : FLAGS.keySet()) {
            if (s.isBoolean(flag)) w.flags.put(flag, s.getBoolean(flag));
        }
        return w;
    }

    void save(ConfigurationSection s) {
        s.set("type", type);
        s.set("environment", environment);
        s.set("autoload", autoload);
        s.set("gamemode", gamemode == null ? "geen" : gamemode.name().toLowerCase(Locale.ROOT));
        for (String flag : FLAGS.keySet()) s.set(flag, flag(flag));
    }

    /** "survival", "creative" of "adventure"; al het andere (zoals "geen") = null, niet aanpassen. */
    static GameMode parseGameMode(String text) {
        switch (text.toLowerCase(Locale.ROOT)) {
            case "survival": return GameMode.SURVIVAL;
            case "creative": return GameMode.CREATIVE;
            case "adventure": return GameMode.ADVENTURE;
            default: return null;
        }
    }
}
