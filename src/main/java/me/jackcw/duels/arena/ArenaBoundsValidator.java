package me.jackcw.duels.arena;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Checks whether a bounds box has an opening a liquid could try to escape
 * through, so an admin finds out at setup time rather than mid-match.
 *
 * <p>The motivating problem is liquid. Minecraft chooses which way a fluid will
 * spread <em>before</em> any event fires, and it does not pick every open
 * neighbour: if the fluid can fall it commits to falling, and otherwise it
 * flows only towards the nearest place it could fall. {@link
 * ArenaContainmentGuard} then vetoes that one direction when it leaves the
 * arena - but a veto is not a redirect, so if the direction Minecraft picked
 * was out through an opening, the liquid spreads nowhere at all and looks
 * frozen, even with open space inside the arena. Nothing escapes, which is why
 * this is advice rather than an error, but "my lava won't move" reads as a
 * plugin bug.
 *
 * <p>The test that matters is <em>not</em> whether the box's own outermost
 * layer is made of solid blocks. That would condemn the normal setup, where an
 * admin sets the bounds to the arena's interior and the walls sit one block
 * outside the box: every boundary block is then air, yet nothing can escape,
 * because the real walls stop it. An opening needs two things at once:
 *
 * <ul>
 *   <li>the boundary block is passable, so a liquid can actually be there;</li>
 *   <li>the block immediately <em>outside</em> the box in that direction is
 *       also passable, so Minecraft may choose to spread that way.</li>
 * </ul>
 *
 * <p>Together those flag the case that genuinely misbehaves - a doorway,
 * window or hole in the floor - while staying quiet for a sealed room whether
 * the admin drew the box around the interior or around the walls themselves.
 *
 * <p>The roof is deliberately not inspected. Fluid never flows upward, so an
 * open-topped arena is a normal design rather than a mistake, and warning about
 * it would train admins to ignore the warning.
 */
public final class ArenaBoundsValidator
{
    /**
     * Ceiling on how many blocks one check may read. A bounds box can
     * legitimately be enormous, and this runs on the main thread in response to
     * an admin click, so an unbounded scan of a 300x100x300 box would stall the
     * server. Past this size the check reports itself as not run rather than
     * doing partial work and drawing a conclusion from it.
     */
    private static final int MAX_INSPECTED_BLOCKS = 20_000;

    private ArenaBoundsValidator()
    {
    }

    /**
     * What an inspection found.
     *
     * @param inspected     whether the check actually ran - {@code false} when
     *                      the box was too large or part of it was in unloaded
     *                      chunks, in which case the counts carry no meaning
     * @param floorOpenings boundary blocks a liquid could fall out through
     * @param wallOpenings  boundary blocks a liquid could flow sideways out
     *                      through
     */
    public record OpeningReport(boolean inspected, int floorOpenings, int wallOpenings)
    {
        private static final OpeningReport NOT_INSPECTED = new OpeningReport(false, 0, 0);

        public int totalOpenings()
        {
            return floorOpenings + wallOpenings;
        }

        public boolean hasOpenings()
        {
            return inspected && totalOpenings() > 0;
        }
    }

    /**
     * Inspects the box, skipping the work when it would be unsafe or too
     * expensive - see {@link #MAX_INSPECTED_BLOCKS} and {@link
     * #fullyLoaded(BlockBox)}. This is the entry point callers should use.
     */
    public static OpeningReport inspect(BlockBox box)
    {
        if (box == null || !withinBudget(box) || !fullyLoaded(box))
            return OpeningReport.NOT_INSPECTED;

        return countOpenings(box);
    }

    /**
     * Counts the openings, assuming the caller has already established that
     * reading the box is safe. Prefer {@link #inspect(BlockBox)}, which applies
     * that check itself; this is separate only because the check depends on
     * chunk residency, which is not something a caller can assert in a test.
     */
    public static OpeningReport countOpenings(BlockBox box)
    {
        if (box == null)
            return OpeningReport.NOT_INSPECTED;

        World world = box.world();
        int floorOpenings = 0;
        int wallOpenings = 0;

        for (int y = box.minY(); y <= box.maxY(); y++)
            for (int x = box.minX(); x <= box.maxX(); x++)
                for (int z = box.minZ(); z <= box.maxZ(); z++)
                {
                    boolean onFloor = y == box.minY();
                    boolean onSide = x == box.minX() || x == box.maxX() || z == box.minZ() || z == box.maxZ();

                    // Interior blocks have no outward neighbour, and the roof is
                    // exempt, so most of the volume is skipped immediately.
                    if (!onFloor && !onSide)
                        continue;

                    // Nothing can leak from a block a liquid cannot occupy, so
                    // this check comes first and spares the neighbour lookups.
                    if (!passable(world, x, y, z))
                        continue;

                    // Down is checked before sideways, and a position is counted
                    // once, because the two are not independent: a liquid that
                    // can fall out will fall rather than flow, so the floor is
                    // the opening the admin should hear about.
                    if (onFloor && passable(world, x, y - 1, z))
                        floorOpenings++;
                    else if (onSide && leaksSideways(world, box, x, y, z))
                        wallOpenings++;
                }

        return new OpeningReport(true, floorOpenings, wallOpenings);
    }

    private static boolean leaksSideways(World world, BlockBox box, int x, int y, int z)
    {
        return (x == box.minX() && passable(world, x - 1, y, z))
                || (x == box.maxX() && passable(world, x + 1, y, z))
                || (z == box.minZ() && passable(world, x, y, z - 1))
                || (z == box.maxZ() && passable(world, x, y, z + 1));
    }

    private static boolean passable(World world, int x, int y, int z)
    {
        Block block = world.getBlockAt(x, y, z);

        // Solidity rather than emptiness, because the question is whether a
        // fluid could be here or move through: air, water, lava, grass and
        // torches are all passable as far as containment is concerned.
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
     * Whether every chunk the check touches is already loaded.
     *
     * <p>Reading a block in an unloaded chunk makes the server load it
     * synchronously. Doing that across a whole arena to produce an advisory
     * message would be a worse problem than the one being reported, so an
     * unloaded box means the check is skipped silently instead.
     *
     * <p>The range is widened by one block on each side because the check reads
     * the neighbours just outside the box, which can fall in the next chunk
     * along.
     */
    private static boolean fullyLoaded(BlockBox box)
    {
        World world = box.world();

        for (int chunkX = (box.minX() - 1) >> 4; chunkX <= (box.maxX() + 1) >> 4; chunkX++)
            for (int chunkZ = (box.minZ() - 1) >> 4; chunkZ <= (box.maxZ() + 1) >> 4; chunkZ++)
                if (!world.isChunkLoaded(chunkX, chunkZ))
                    return false;

        return true;
    }
}
