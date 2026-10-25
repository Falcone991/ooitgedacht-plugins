package com.example.ooitbingo;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.map.MinecraftFont;

import java.awt.Color;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tekent de bingokaart op de map, per speler (eigen vinkjes): 5x5 vakjes in de kleur
 * van het blok, met een groen vinkje als je hem hebt. Alleen opnieuw tekenen als er iets
 * veranderd is.
 */
final class BingoRenderer extends MapRenderer {

    private static final Color BACKGROUND = new Color(230, 211, 163);
    private static final Color LINE = new Color(90, 70, 40);
    private static final Color CHECK = new Color(30, 200, 40);
    private static final int GAP = 3;
    private static final int TOP = 12;
    // Zo groot mogelijk: past in de hoogte onder de titel (128 - TOP - 2 pixels rand).
    private static final int CELL = (128 - TOP - 2 - (OoitBingo.SIZE - 1) * GAP) / OoitBingo.SIZE;
    private static final int LEFT = (128 - (OoitBingo.SIZE * CELL + (OoitBingo.SIZE - 1) * GAP)) / 2;

    private final OoitBingo plugin;
    private final Map<UUID, Integer> drawn = new HashMap<>();

    BingoRenderer(OoitBingo plugin) {
        super(true); // per speler een eigen tekening
        this.plugin = plugin;
    }

    @Override
    public void render(MapView view, MapCanvas canvas, Player player) {
        Integer last = drawn.get(player.getUniqueId());
        if (last != null && last == plugin.version) return;
        drawn.put(player.getUniqueId(), plugin.version);

        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) canvas.setPixelColor(x, y, BACKGROUND);
        Set<Integer> mine = plugin.found.getOrDefault(player.getUniqueId(), Collections.emptySet());
        String title = !plugin.active ? (plugin.winner != null ? "Winnaar: " + plugin.winner : "Bingo gestopt")
                : "BINGO  " + mine.size() + "/" + (OoitBingo.SIZE * OoitBingo.SIZE);
        canvas.drawText(Math.max(1, (128 - MinecraftFont.Font.getWidth(safe(title))) / 2), 2, MinecraftFont.Font, safe(title));

        for (int i = 0; i < plugin.card.size() && i < OoitBingo.SIZE * OoitBingo.SIZE; i++) {
            int x0 = LEFT + (i % OoitBingo.SIZE) * (CELL + GAP);
            int y0 = TOP + (i / OoitBingo.SIZE) * (CELL + GAP);
            Color fill = blockColor(plugin.card.get(i));
            boolean got = mine.contains(i);
            for (int x = 0; x < CELL; x++) {
                for (int y = 0; y < CELL; y++) {
                    boolean border = x == 0 || y == 0 || x == CELL - 1 || y == CELL - 1;
                    canvas.setPixelColor(x0 + x, y0 + y, border ? (got ? CHECK : LINE) : fill);
                }
            }
            if (got) drawCheck(canvas, x0, y0);
        }
    }

    /** Dik vinkje in het vakje, meegeschaald met de grootte van het vakje. */
    private static void drawCheck(MapCanvas canvas, int x0, int y0) {
        double s = CELL / 22.0;                 // ontwerp is voor een vakje van 22 pixels
        int thick = Math.max(2, (int) Math.round(2 * s));
        for (double t = 0; t <= 1.0; t += 0.02) {
            // korte poot: (5,11) -> (10,16); lange poot: (10,16) -> (19,7)
            double[][] line = t <= 0.35
                    ? new double[][]{{5 + 5 * (t / 0.35), 11 + 5 * (t / 0.35)}}
                    : new double[][]{{10 + 9 * ((t - 0.35) / 0.65), 16 - 9 * ((t - 0.35) / 0.65)}};
            int px = x0 + (int) Math.round(line[0][0] * s);
            int py = y0 + (int) Math.round(line[0][1] * s);
            for (int dx = 0; dx < thick; dx++) {
                for (int dy = 0; dy < thick; dy++) {
                    int x = px + dx;
                    int y = py + dy;
                    if (x < x0 + CELL - 1 && y < y0 + CELL - 1) canvas.setPixelColor(x, y, CHECK);
                }
            }
        }
    }

    private static Color blockColor(Material m) {
        try {
            if (m.isBlock()) {
                org.bukkit.Color c = m.createBlockData().getMapColor();
                if (c != null && (c.getRed() + c.getGreen() + c.getBlue()) > 0) return new Color(c.getRed(), c.getGreen(), c.getBlue());
            }
        } catch (RuntimeException ignored) {
            // geen kleur bekend
        }
        return new Color(150, 150, 150);
    }

    /** MinecraftFont kent niet alle tekens; alleen gewone letters, cijfers en leestekens. */
    private static String safe(String text) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) sb.append(MinecraftFont.Font.isValid(String.valueOf(ch)) ? ch : '?');
        return sb.toString();
    }
}
