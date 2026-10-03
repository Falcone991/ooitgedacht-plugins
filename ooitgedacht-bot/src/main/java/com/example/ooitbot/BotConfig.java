package com.example.ooitbot;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Leest config.yml. Bestaat die nog niet, dan wordt de standaardversie (met
 * uitleg) uit de jar ernaast gezet. Ontbrekende instellingen vallen terug op
 * de standaardwaarde in de code.
 */
final class BotConfig {

    private final Map<String, Object> root;

    private BotConfig(Map<String, Object> root) {
        this.root = root;
    }

    /** @return true als er net een nieuwe config.yml is aangemaakt. */
    static boolean createDefaultIfMissing(Path file) throws IOException {
        if (Files.exists(file)) return false;
        try (InputStream in = BotConfig.class.getResourceAsStream("/config.yml")) {
            if (in == null) throw new IOException("config.yml ontbreekt in de jar");
            Files.copy(in, file);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    static BotConfig load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object loaded = new Yaml().load(reader);
            return new BotConfig(loaded instanceof Map ? (Map<String, Object>) loaded : Collections.emptyMap());
        }
    }

    /** Waarde op een pad als "bot.token", of null. */
    @SuppressWarnings("unchecked")
    private Object get(String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map)) return null;
            current = ((Map<String, Object>) current).get(part);
        }
        return current;
    }

    String text(String path, String def) {
        Object value = get(path);
        return value == null ? def : String.valueOf(value);
    }

    /** Voor ID's, tokens en wachtwoorden: zonder spaties eromheen. */
    String id(String path) {
        return text(path, "").trim();
    }

    int integer(String path, int def) {
        Object value = get(path);
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return value == null ? def : Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    boolean bool(String path, boolean def) {
        Object value = get(path);
        if (value instanceof Boolean) return (Boolean) value;
        return value == null ? def : Boolean.parseBoolean(String.valueOf(value).trim());
    }

    List<String> list(String path) {
        Object value = get(path);
        List<String> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object item : (List<?>) value) result.add(String.valueOf(item).trim());
        }
        return result;
    }
}
