package com.example.ooitbot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Verbinding met de DiscordBridge-plugin op de Minecraft-server, via RCON.
 * Houdt bij of de server wakker is, en haalt nieuwe logs op.
 */
final class ServerLink {

    interface Callbacks {
        void serverWokeUp();

        void serverWentToSleep();

        void events(List<JsonNode> events);
    }

    // Pas na zoveel mislukte pogingen op rij "slaapt" aannemen, zodat één
    // haperende verbinding (bv. even lag) niet meteen een slaap-melding geeft.
    private static final int FAILS_BEFORE_OFFLINE = 2;

    private final Rcon rcon;
    private final Callbacks callbacks;
    private final ObjectMapper mapper = new ObjectMapper();

    private volatile boolean online = false;
    private volatile List<String> players = Collections.emptyList();
    private volatile int maxPlayers = 0;
    private int failures = 0;
    private String lastWarning = null;

    ServerLink(Rcon rcon, Callbacks callbacks) {
        this.rcon = rcon;
        this.callbacks = callbacks;
    }

    boolean isOnline() {
        return online;
    }

    List<String> players() {
        return players;
    }

    int maxPlayers() {
        return maxPlayers;
    }

    ObjectNode request(String op) {
        return mapper.createObjectNode().put("op", op);
    }

    /** Stuurt een verzoek naar de plugin en geeft het JSON-antwoord terug. */
    JsonNode rpc(ObjectNode request) throws IOException {
        String encoded = Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(request));
        String response = rcon.command("discordbridge rpc " + encoded);

        int start = response.indexOf('{');
        if (start < 0) {
            throw new IOException("Onverwacht antwoord van de server (staat de DiscordBridge-plugin erop?): "
                    + (response.isBlank() ? "(leeg)" : response.trim()));
        }
        return mapper.readTree(response.substring(start).getBytes(StandardCharsets.UTF_8));
    }

    /** Eén ronde logs ophalen. Draait op de scheduler-thread. */
    void poll() {
        try {
            int rounds = 0;
            boolean more;
            do {
                JsonNode response = rpc(request("poll"));
                failures = 0;
                lastWarning = null;
                updatePlayers(response);
                if (!online) {
                    online = true;
                    Log.info("Verbonden met de Minecraft-server.");
                    callbacks.serverWokeUp();
                }

                List<JsonNode> events = new ArrayList<>();
                response.path("events").forEach(events::add);
                if (!events.isEmpty()) callbacks.events(events);
                more = response.path("more").asBoolean(false);
            } while (more && ++rounds < 10);
        } catch (IOException e) {
            failed(e);
        }
    }

    private void updatePlayers(JsonNode response) {
        List<String> names = new ArrayList<>();
        response.path("players").forEach(n -> names.add(n.asText()));
        players = Collections.unmodifiableList(names);
        maxPlayers = response.path("max").asInt(maxPlayers);
    }

    private void failed(IOException e) {
        failures++;
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        // Zelfde fout niet elke 10 seconden opnieuw in de console zetten.
        if (!message.equals(lastWarning)) {
            Log.info("Minecraft-server niet bereikbaar (slaapt hij?): " + message);
            lastWarning = message;
        }
        if (online && failures >= FAILS_BEFORE_OFFLINE) {
            online = false;
            players = Collections.emptyList();
            callbacks.serverWentToSleep();
        }
    }
}
