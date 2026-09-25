package me.jackcw.duels.arena;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * An axis-aligned box of whole blocks, defined by two opposite corner blocks
 * that are both inside it.
 *
 * <p>Exists because "a box from two corners" had been worked out independently
 * in {@link ArenaInstance}, in the edit-mode particle renderer, and again in
 * {@link ArenaBoundsValidator} - and the same off-by-one appeared in two of
 * them. The inclusive-corner rule is the part that is easy to get wrong: a box
 * whose corner blocks are X=10 and X=12 is <em>three</em> blocks wide and
 * occupies the space from 10.0 up to 13.0, so code that describes the box in
 * continuous coordinates needs {@link #maxCornerX()} rather than
 * {@link #maxX()}. Keeping both forms on one type makes the choice explicit at
 * every call site instead of implicit in an arithmetic expression.
 */
public record BlockBox(World world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ)
{
    /**
     * The box containing both given locations, whichever order they are in and
     * wherever within their blocks they fall.
     *
     * <p>Returns {@code null} if either corner is missing or the two are in
     * different worlds, because neither case describes a real volume.
     */
    public static BlockBox of(Location corner1, Location corner2)
    {
        if (corner1 == null || corner2 == null || corner1.getWorld() == null
                || !corner1.getWorld().equals(corner2.getWorld()))
            return null;

        int x1 = BlockCoordinates.blockCoordinate(corner1.getX());
        int y1 = BlockCoordinates.blockCoordinate(corner1.getY());
        int z1 = BlockCoordinates.blockCoordinate(corner1.getZ());
        int x2 = BlockCoordinates.blockCoordinate(corner2.getX());
        int y2 = BlockCoordinates.blockCoordinate(corner2.getY());
        int z2 = BlockCoordinates.blockCoordinate(corner2.getZ());

        return new BlockBox(corner1.getWorld(),
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    public boolean contains(Location location)
    {
        if (location.getWorld() == null || !location.getWorld().equals(world))
            return false;

        return contains(BlockCoordinates.blockCoordinate(location.getX()),
                BlockCoordinates.blockCoordinate(location.getY()),
                BlockCoordinates.blockCoordinate(location.getZ()));
    }

    public boolean contains(int x, int y, int z)
    {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public int sizeX()
    {
        return maxX - minX + 1;
    }

    public int sizeY()
    {
        return maxY - minY + 1;
    }

    public int sizeZ()
    {
        return maxZ - minZ + 1;
    }

    public ArenaStructureSize size()
    {
        return new ArenaStructureSize(sizeX(), sizeY(), sizeZ());
    }

    /**
     * The far edge of the box in continuous coordinates - one past the maximum
     * block, because that block occupies space up to its own far face.
     *
     * <p>This is the value anything drawing or measuring the box in world space
     * needs. Using {@link #maxX()} there outlines a box one block short on
     * every maximum face.
     */
    public double maxCornerX()
    {
        return maxX + 1.0;
    }

    public double maxCornerY()
    {
        return maxY + 1.0;
    }

    public double maxCornerZ()
    {
        return maxZ + 1.0;
    }
}
