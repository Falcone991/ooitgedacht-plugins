package com.example.playerstats;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Houthakken: XP per houtblok van een echte boom (met natuurlijke bladeren, dus niet
 * van zelf neergezette blokken). Op hogere levels hak je met een bijl meerdere blokken
 * van dezelfde boom tegelijk; bukken = gewoon één blok (handig bij het bouwen).
 */
final class Woodcutting implements Listener {

    private static final int SEARCH_LIMIT = 400; // nooit meer blokken bekijken dan dit (tegen lag)

    private final PlayerStats plugin;
    private final int levelsPerBlock;
    private final int maxBlocks;
    private final Set<UUID> felling = new HashSet<>();

    Woodcutting(PlayerStats plugin, ConfigurationSection c) {
        this.plugin = plugin;
        levelsPerBlock = c == null ? 10 : Math.max(1, c.getInt("levels-per-extra-block", 10));
        maxBlocks = c == null ? 10 : Math.max(1, c.getInt("max-blocks", 10));
        bigLevel = c == null ? 100 : c.getInt("big-boost-level", 100);
        bigBlocks = c == null ? 100 : Math.max(1, c.getInt("big-boost-blocks", 100));
    }

    private final int bigLevel;
    private final int bigBlocks;

    /** Hoeveel blokken je per hak tegelijk omhakt: elke 10 levels één meer, op level 100 de hele boom. */
    int blocksAt(int level) {
        if (bigLevel > 0 && level >= bigLevel) return bigBlocks;
        return Math.min(maxBlocks, 1 + level / levelsPerBlock);
    }

    /** Vanaf welk level je er weer meer bij krijgt, of -1 als je al op het maximum zit. */
    int nextBoostLevel(int level) {
        if (bigLevel > 0 && level >= bigLevel) return -1;
        if (blocksAt(level) >= maxBlocks) return bigLevel > 0 ? bigLevel : -1;
        int next = (level / levelsPerBlock + 1) * levelsPerBlock;
        return bigLevel > 0 ? Math.min(next, bigLevel) : next;
    }

    /** Hakt deze speler nu een boom om (dan tellen de extra blokken zonder nieuwe controle)? */
    boolean isFelling(UUID uuid) {
        return felling.contains(uuid);
    }

    static boolean isLog(Material type) {
        return Tag.LOGS.isTagged(type);
    }

    /**
     * Hoort dit houtblok bij een echte boom? Dan zitten er natuurlijke (niet neergezette) bladeren
     * vlakbij, of bovenaan de stam (bij hoge bomen zit het blad ver boven het onderste blok).
     */
    static boolean isTreeLog(Block block) {
        if (nearNaturalLeaves(block, 2)) return true;
        Block top = block;
        for (int i = 0; i < 40 && isLog(top.getRelative(0, 1, 0).getType()); i++) top = top.getRelative(0, 1, 0);
        return top != block && nearNaturalLeaves(top, 2);
    }

    private static boolean nearNaturalLeaves(Block block, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Block b = block.getRelative(dx, dy, dz);
                    if (b.getBlockData() instanceof Leaves && !((Leaves) b.getBlockData()).isPersistent()) return true;
                }
            }
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block origin = event.getBlock();
        if (felling.contains(player.getUniqueId()) || player.isSneaking()) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) return;
        if (!isLog(origin.getType())) return;
        if (!player.getInventory().getItemInMainHand().getType().name().endsWith("_AXE")) return;
        int max = blocksAt(plugin.getLevel(player.getUniqueId(), "woodcutting"));
        if (max <= 1) return;

        List<Block> extra = collect(origin, max - 1);
        if (extra.isEmpty() || !isTreeLog(origin)) return; // geen echte boom (bv. een huis van hout): niets extra

        // Volgende tick: het eerste blok is dan echt weg. breakBlock = alsof de speler zelf hakt
        // (bijl slijt, bescherming en andere plugins tellen gewoon mee).
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            felling.add(player.getUniqueId());
            try {
                for (Block b : extra) {
                    ItemStack tool = player.getInventory().getItemInMainHand();
                    if (!tool.getType().name().endsWith("_AXE")) break; // bijl kapot of weggelegd
                    if (isLog(b.getType())) player.breakBlock(b);
                }
            } finally {
                felling.remove(player.getUniqueId());
            }
        });
    }

    /** Aaneengesloten houtblokken rond het eerste blok, dichtstbijzijnde eerst, hooguit 'limit'. */
    private static List<Block> collect(Block origin, int limit) {
        List<Block> found = new ArrayList<>();
        Set<Block> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();
        seen.add(origin);
        queue.add(origin);
        int checked = 0;
        while (!queue.isEmpty() && found.size() < limit && checked++ < SEARCH_LIMIT) {
            Block current = queue.poll();
            // Eerst omhoog en opzij, dan omlaag: zo gaat de boom van onder naar boven weg.
            for (int dy = 1; dy >= -1; dy--) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Block next = current.getRelative(dx, dy, dz);
                        if (!seen.add(next) || !isLog(next.getType())) continue;
                        found.add(next);
                        queue.add(next);
                        if (found.size() >= limit) return found;
                    }
                }
            }
        }
        return found;
    }
}
