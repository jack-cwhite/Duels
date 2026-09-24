package me.jackcw.duels.arena;

/**
 * The dimensions of an arena block box, in blocks.
 *
 * <p>Named for its original use, a captured arena structure, but it carries no
 * structure-specific behaviour and {@link ArenaInstance#getBoundsSize()} reuses
 * it for bounds rather than introducing a second identical three-integer record.
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
