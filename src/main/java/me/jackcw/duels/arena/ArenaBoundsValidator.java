package me.jackcw.duels.arena;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Checks whether a bounds box is actually sealed by solid blocks, so an admin
 * finds out at setup time rather than mid-match.
 *
 * <p>The motivating problem is liquid. Minecraft chooses which way a fluid will
 * spread <em>before</em> any event fires, and it does not pick every open
 * neighbour: if the fluid can fall it commits to falling, and otherwise it
 * flows only towards the nearest place it could fall. {@link
 * ArenaContainmentGuard} then vetoes that one direction when it leaves the
 * arena - but a veto is not a redirect, so if the direction Minecraft picked
 * was out through a gap in the floor or a wall, the liquid spreads nowhere at
 * all and looks frozen, even with open space inside the arena. Nothing escapes,
 * which is why this is advice rather than an error, but "my lava won't move"
 * reads as a plugin bug.
 *
 * <p>The same gaps cause a second, quieter problem: the rollback strategy only
 * records changes inside the bounds, so a floor or wall left outside the box is
 * never restored after a match damages it.
 *
 * <p>The roof is deliberately not inspected. Fluid never flows upward and an
 * open-topped arena has no ceiling blocks to restore, so an open roof is a
 * normal design rather than a mistake, and warning about it would train admins
 * to ignore the warning.
 */
public final class ArenaBoundsValidator
{
    /**
     * Ceiling on how many blocks one check may read. A bounds box can
     * legitimately be enormous, and this runs on the main thread in response to
     * an admin click, so an unbounded shell scan on a 300x100x300 box would
     * stall the server. Past this size the check reports itself as not run
     * rather than doing partial work and drawing a conclusion from it.
     */
    private static final int MAX_INSPECTED_BLOCKS = 20_000;

    private ArenaBoundsValidator()
    {
    }

    /**
     * What a shell inspection found.
     *
     * @param inspected whether the check actually ran - {@code false} when the
     *                  box was too large or part of it was in unloaded chunks,
     *                  in which case the gap counts carry no meaning
     * @param floorGaps non-solid blocks in the bottom layer of the box
     * @param wallGaps  non-solid blocks in the four side faces
     */
    public record ShellReport(boolean inspected, int floorGaps, int wallGaps)
    {
        private static final ShellReport NOT_INSPECTED = new ShellReport(false, 0, 0);

        public int totalGaps()
        {
            return floorGaps + wallGaps;
        }

        public boolean hasGaps()
        {
            return inspected && totalGaps() > 0;
        }
    }

    /**
     * Inspects the shell, skipping the work when it would be unsafe or too
     * expensive - see {@link #MAX_INSPECTED_BLOCKS} and {@link
     * #fullyLoaded(BlockBox)}. This is the entry point callers should use.
     */
    public static ShellReport inspectShell(BlockBox box)
    {
        if (box == null || !withinBudget(box) || !fullyLoaded(box))
            return ShellReport.NOT_INSPECTED;

        return countShellGaps(box);
    }

    /**
     * Counts the gaps, assuming the caller has already established that reading
     * the box is safe. Prefer {@link #inspectShell(BlockBox)}, which applies
     * that check itself; this is separate only because the check depends on
     * chunk residency, which is not something a caller can assert in a test.
     */
    public static ShellReport countShellGaps(BlockBox box)
    {
        if (box == null)
            return ShellReport.NOT_INSPECTED;

        World world = box.world();
        int floorGaps = 0;
        int wallGaps = 0;

        for (int x = box.minX(); x <= box.maxX(); x++)
            for (int z = box.minZ(); z <= box.maxZ(); z++)
                if (isGap(world, x, box.minY(), z))
                    floorGaps++;

        for (int y = box.minY() + 1; y <= box.maxY(); y++)
            for (int x = box.minX(); x <= box.maxX(); x++)
                for (int z = box.minZ(); z <= box.maxZ(); z++)
                {
                    boolean onSide = x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ();

                    if (onSide && isGap(world, x, y, z))
                        wallGaps++;
                }

        return new ShellReport(true, floorGaps, wallGaps);
    }

    private static boolean isGap(World world, int x, int y, int z)
    {
        Block block = world.getBlockAt(x, y, z);

        // Solidity rather than emptiness, because the question is whether a
        // fluid could pass: air, water, lava, grass and torches are all holes
        // as far as containment is concerned.
        return !block.getType().isSolid();
    }

    private static boolean withinBudget(BlockBox box)
    {
        long floor = (long) box.sizeX() * box.sizeZ();
        long perimeter = 2L * box.sizeX() + 2L * box.sizeZ();
        long walls = perimeter * Math.max(0, box.sizeY() - 1);

        return floor + walls <= MAX_INSPECTED_BLOCKS;
    }

    /**
     * Whether every chunk the shell touches is already loaded.
     *
     * <p>Reading a block in an unloaded chunk makes the server load it
     * synchronously. Doing that across a whole arena to produce an advisory
     * message would be a worse problem than the one being reported, so an
     * unloaded box means the check is skipped silently instead.
     */
    private static boolean fullyLoaded(BlockBox box)
    {
        World world = box.world();

        for (int chunkX = box.minX() >> 4; chunkX <= box.maxX() >> 4; chunkX++)
            for (int chunkZ = box.minZ() >> 4; chunkZ <= box.maxZ() >> 4; chunkZ++)
                if (!world.isChunkLoaded(chunkX, chunkZ))
                    return false;

        return true;
    }
}
