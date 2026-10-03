package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Wat de bot onthoudt tussen herstarts (data.json): ID van het statusbericht,
 * streaks, laatst bekende tijdlijn-info en /deaths-ranglijst (voor als de
 * server slaapt). Alle methodes zijn synchronized: meerdere threads gebruiken dit.
 */
final class BotData {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private ObjectNode root;

    private BotData(Path file, ObjectNode root) {
        this.file = file;
        this.root = root;
    }

    static BotData load(Path file) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        if (Files.exists(file)) {
            try {
                JsonNode loaded = mapper.readTree(file.toFile());
                if (loaded instanceof ObjectNode) root = (ObjectNode) loaded;
            } catch (IOException e) {
                Log.warn("Kon data.json niet lezen, begin met lege data: " + e.getMessage());
            }
        }
        return new BotData(file, root);
    }

    synchronized void save() {
        try {
            // Eerst naar een tijdelijk bestand, dan vervangen: zo raakt data.json
            // nooit half-geschreven als de bot midden in het opslaan stopt.
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Kon data.json niet opslaan: " + e.getMessage());
        }
    }

    synchronized String text(String key) {
        JsonNode node = root.get(key);
        return node == null || node.isNull() ? null : node.asText();
    }

    synchronized void putText(String key, String value) {
        root.put(key, value);
    }

    synchronized long number(String key, long def) {
        JsonNode node = root.get(key);
        return node == null || !node.canConvertToLong() ? def : node.asLong();
    }

    synchronized void putNumber(String key, long value) {
        root.put(key, value);
    }

    synchronized JsonNode node(String key) {
        JsonNode node = root.get(key);
        return node == null ? null : node.deepCopy();
    }

    synchronized void putNode(String key, JsonNode value) {
        if (value == null) root.remove(key);
        else root.set(key, value.deepCopy());
    }

    /** Object onder 'key' (wordt aangemaakt als het nog niet bestaat). Alleen gebruiken binnen synchronized(data). */
    synchronized ObjectNode object(String key) {
        JsonNode node = root.get(key);
        if (node instanceof ObjectNode) return (ObjectNode) node;
        return root.putObject(key);
    }
}
