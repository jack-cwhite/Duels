package me.jackcw.duels.arena;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * An integer block offset from an arena structure's capture origin.
 *
 * <p>Keeping this separate from Bukkit's {@link Location} makes captured
 * arena metadata portable: a dynamic copy can be placed in any slot/world
 * without retaining a reference to the source world.
 */
public record RelativeBlockPosition(int x, int y, int z)
{
    public static RelativeBlockPosition between(Location origin, Location location)
    {
        if (origin == null || location == null || origin.getWorld() == null
                || !origin.getWorld().equals(location.getWorld()))
            throw new IllegalArgumentException("Origin and location must be in the same world");

        return new RelativeBlockPosition(
                location.getBlockX() - origin.getBlockX(),
                location.getBlockY() - origin.getBlockY(),
                location.getBlockZ() - origin.getBlockZ()
        );
    }

    public Location toLocation(World world, int originX, int originY, int originZ)
    {
        if (world == null)
            throw new IllegalArgumentException("World cannot be null");

        return new Location(world, originX + x, originY + y, originZ + z);
    }
}
