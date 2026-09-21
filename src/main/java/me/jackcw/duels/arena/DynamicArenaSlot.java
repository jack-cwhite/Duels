package me.jackcw.duels.arena;

/** One fixed, non-overlapping interior rectangle in the dynamic arena grid. */
public record DynamicArenaSlot(int index, int originX, int originY, int originZ, int width, int length)
{
    public boolean fits(ArenaStructureSize size)
    {
        return size != null && size.x() <= width && size.z() <= length;
    }
}
