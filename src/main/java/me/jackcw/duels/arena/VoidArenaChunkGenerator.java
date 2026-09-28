package me.jackcw.duels.arena;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;

import java.util.Random;

/** Generates an intentionally empty world; all arena blocks come from templates. */
public final class VoidArenaChunkGenerator extends ChunkGenerator
{
    @Override public boolean shouldGenerateNoise(WorldInfo info, Random random, int x, int z) { return false; }
    @Override public boolean shouldGenerateSurface(WorldInfo info, Random random, int x, int z) { return false; }
    @Override public boolean shouldGenerateBedrock() { return false; }
    @Override public boolean shouldGenerateCaves(WorldInfo info, Random random, int x, int z) { return false; }
    @Override public boolean shouldGenerateDecorations(WorldInfo info, Random random, int x, int z) { return false; }
    @Override public boolean shouldGenerateMobs(WorldInfo info, Random random, int x, int z) { return false; }
    @Override public boolean shouldGenerateStructures(WorldInfo info, Random random, int x, int z) { return false; }

    /**
     * Without this, creating a world with this generator stalls the server.
     *
     * <p>A Bukkit generator that returns no fixed spawn leaves the server to run
     * its own spawn search, and vanilla's search walks outward loading chunks on
     * the main thread looking for a solid, non-hazardous block to stand on. In a
     * world that is empty by definition there is no such block, so it generates
     * chunk after chunk until it gives up - long enough that Paper's watchdog
     * printed a thread dump when an arena world was created during testing. It
     * affects both {@code /mv create <name> normal -g Duels} and Duels creating
     * its own dynamic arena world for the first time.
     *
     * <p>The location is deliberately still in the void rather than on a
     * platform. Nobody arrives in an arena world by accident - duellists are
     * teleported to arena spawns - and the dynamic arena world in particular has
     * to stay genuinely empty, because a generated copy is verified against the
     * template it came from and a stray platform inside a slot would show up as
     * a mismatch. An admin creating a world for static arenas builds their own
     * ground, which is what the config comment tells them to do.
     */
    @Override
    public Location getFixedSpawnLocation(World world, Random random)
    {
        return new Location(world, 0.5, 64.0, 0.5);
    }
}
