package me.jackcw.duels.arena;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * A spawn position relative to a captured structure, including its view
 * direction. Block-relative coordinates deliberately keep all generated
 * copies aligned to the same build grid.
 */
public record RelativeArenaLocation(int x, int y, int z, float yaw, float pitch)
{
    public static RelativeArenaLocation between(Location origin, Location location)
    {
        RelativeBlockPosition relative = RelativeBlockPosition.between(origin, location);
        return new RelativeArenaLocation(relative.x(), relative.y(), relative.z(), location.getYaw(), location.getPitch());
    }

    public Location toLocation(World world, int originX, int originY, int originZ)
    {
        if (world == null)
            throw new IllegalArgumentException("World cannot be null");

        return new Location(world, originX + x, originY + y, originZ + z, yaw, pitch);
    }
}
