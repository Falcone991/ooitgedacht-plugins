package com.example.ooitworlds;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;

import java.util.Random;

/** Lege wereld: geen grond, geen grotten, geen structuren, geen mobs. */
final class VoidGenerator extends ChunkGenerator {

    @Override
    public Location getFixedSpawnLocation(World world, Random random) {
        return new Location(world, 0.5, 65, 0.5);
    }
}
