package me.jackcw.duels.arena;

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
}
