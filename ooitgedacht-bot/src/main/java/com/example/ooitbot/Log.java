package com.example.ooitbot;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Simpele console-logging; AMP toont alles wat naar de console gaat. */
final class Log {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Log() {
    }

    static void info(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + " INFO] " + message);
    }

    static void warn(String message) {
        System.out.println("[" + LocalTime.now().format(TIME) + " WARN] " + message);
    }
}
