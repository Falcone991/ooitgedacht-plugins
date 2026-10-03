package com.example.discordbridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import java.util.function.Consumer;

/**
 * Leest de server-console mee op zoek naar vanilla's "moved too quickly!"- en
 * "moved wrongly!"-waarschuwingen. Daar bestaat geen Bukkit-event voor, dus
 * hangen we een extra "luisteraar" aan het logsysteem van de server.
 *
 * Staat in een eigen klasse zodat de rest van de plugin gewoon blijft werken
 * als het logsysteem ooit anders in elkaar zit.
 */
class MoveWarningWatcher extends AbstractAppender {

    private final Consumer<String> onWarning;
    private final boolean includeMovedWrongly;

    MoveWarningWatcher(Consumer<String> onWarning, boolean includeMovedWrongly) {
        super("DiscordBridgeMoveWatcher", null, null, true, Property.EMPTY_ARRAY);
        this.onWarning = onWarning;
        this.includeMovedWrongly = includeMovedWrongly;
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        if (message == null) return;

        if (message.contains(" moved too quickly!")
                || (includeMovedWrongly && message.contains(" moved wrongly!"))) {
            onWarning.accept(message);
        }
    }

    void install() {
        start();
        rootLogger().addAppender(this);
    }

    void uninstall() {
        rootLogger().removeAppender(this);
        stop();
    }

    private static Logger rootLogger() {
        return (Logger) LogManager.getRootLogger();
    }
}
