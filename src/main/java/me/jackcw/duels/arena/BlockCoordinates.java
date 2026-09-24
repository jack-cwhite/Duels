package me.jackcw.duels.arena;

import org.bukkit.Location;

/**
 * Converts the continuous positions Bukkit reports into the whole-block
 * coordinates that arena boxes are actually defined in.
 *
 * <p>This exists because the two coordinate systems are easy to mix up with no
 * compiler complaint. A player's {@code getLocation()} is a point anywhere
 * inside a block - standing in the middle of block X=10 reports X=10.5 - while
 * {@code Block.getLocation()} reports that block's <em>minimum corner</em>,
 * X=10.0. Comparing the two directly makes a box defined from a standing
 * position silently exclude its own minimum face: the block the admin was
 * standing in fails {@code 10.0 >= 10.5}, while the opposite corner passes.
 * Reducing both sides to a block index first removes the whole class of
 * mistake.
 *
 * <p>Shared rather than duplicated because {@link ArenaInstance},
 * {@link ArenaInstanceManager}, {@link ArenaEditManager} and
 * {@link ArenaTemplateManager} all need the same answer, and a tolerance
 * constant copied into four places is a tolerance constant that eventually
 * disagrees with itself.
 */
public final class BlockCoordinates
{
    /**
     * Client-reported position is float-precision, so a foot location that is
     * conceptually on a block boundary (e.g. 64.0) can arrive as 63.999997 and
     * floor to the block below. A small tolerance before flooring absorbs that
     * drift without risking a false match across an intentionally different
     * block.
     */
    private static final double BLOCK_COORD_EPSILON = 1.0e-3;

    private BlockCoordinates()
    {
    }

    /**
     * The index of the block containing this coordinate.
     *
     * <p>{@code Math.floor} rather than a cast to {@code int}, because a cast
     * truncates towards zero: block -11 spans -11.0 to -10.0, so a player at
     * -10.5 is inside block -11, which flooring gives and truncation does not.
     */
    public static int blockCoordinate(double value)
    {
        return (int) Math.floor(value + BLOCK_COORD_EPSILON);
    }

    /**
     * The minimum corner of the block containing this location.
     *
     * <p>Yaw and pitch are deliberately dropped. A corner marks a position in
     * the world, not a direction to face, and carrying a stale facing would
     * make "teleport me to this corner" point wherever the admin happened to be
     * looking when they set it. Spawn points, which genuinely do need a facing,
     * are stored as the raw player location instead and must not be passed
     * through here.
     */
    public static Location snapToBlock(Location location)
    {
        return new Location(
                location.getWorld(),
                blockCoordinate(location.getX()),
                blockCoordinate(location.getY()),
                blockCoordinate(location.getZ())
        );
    }
}
