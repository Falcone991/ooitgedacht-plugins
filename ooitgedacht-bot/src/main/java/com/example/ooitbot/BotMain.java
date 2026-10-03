package com.example.ooitbot;

import java.nio.file.Path;
import java.nio.file.Paths;

/** Startpunt: java -jar ooitgedacht-bot.jar (leest config.yml uit de huidige map). */
public final class BotMain {

    private BotMain() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Paths.get("").toAbsolutePath();
        Path configFile = dir.resolve("config.yml");

        if (BotConfig.createDefaultIfMissing(configFile)) {
            Log.info("Nieuwe config.yml aangemaakt in " + dir + ".");
            Log.info("Vul daarin de bot-token, guild-id, kanalen en het RCON-wachtwoord in en start de bot opnieuw.");
            return;
        }

        BotConfig config = BotConfig.load(configFile);
        if (config.id("bot.token").isEmpty()) {
            Log.warn("Geen bot-token ingesteld in " + configFile + ". Vul bot.token in en start de bot opnieuw.");
            return;
        }

        BotData data = BotData.load(dir.resolve("data.json"));
        new Bot(config, data).start();
    }
}
