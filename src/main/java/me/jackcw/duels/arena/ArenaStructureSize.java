package me.jackcw.duels.arena;

/**
 * The dimensions of a captured arena structure in blocks.
 */
public record ArenaStructureSize(int x, int y, int z)
{
    public ArenaStructureSize
    {
        if (x <= 0 || y <= 0 || z <= 0)
            throw new IllegalArgumentException("Structure dimensions must be positive");
    }

    public long volume()
    {
        return (long) x * y * z;
    }
}
