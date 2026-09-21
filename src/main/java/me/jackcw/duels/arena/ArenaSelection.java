package me.jackcw.duels.arena;

/** A challenge/queue arena preference: any viable template or one exact ID. */
public record ArenaSelection(Integer arenaId)
{
    public static ArenaSelection any() { return new ArenaSelection(null); }
    public static ArenaSelection specific(int arenaId)
    {
        if (arenaId < 1)
            throw new IllegalArgumentException("Arena id must be positive");
        return new ArenaSelection(arenaId);
    }

    public boolean isAny() { return arenaId == null; }
}
