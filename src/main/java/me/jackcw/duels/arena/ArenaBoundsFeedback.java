package me.jackcw.duels.arena;

import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Reports a bounds corner back to the admin who just set it.
 *
 * <p>Shared because a corner can be set from three places - the edit-mode tool,
 * the instance menu, and {@code /duels} - and the useful part of the
 * confirmation is a rule rather than a string: show the corner's block
 * coordinates always, and the resulting box size only once both corners exist.
 * Reimplementing that in three packages is how one of them ends up silently
 * dropping the size.
 *
 * <p>The size is the feedback that actually catches mistakes. A pair of
 * coordinates looks equally plausible whether or not it is a block out; "20x5x20
 * blocks" when the arena is five blocks tall and you expected six does not.
 */
public final class ArenaBoundsFeedback
{
    private ArenaBoundsFeedback()
    {
    }

    public static void sendCornerSet(MessageManager messageManager, Player player, ArenaInstance instance, int corner)
    {
        Location location = corner == 1 ? instance.getBoundsCorner1() : instance.getBoundsCorner2();

        messageManager.send(player, Message.ARENA_BOUNDS_SET,
                "corner", corner,
                "id", instance.getId(),
                "x", location.getBlockX(),
                "y", location.getBlockY(),
                "z", location.getBlockZ());

        BlockBox box = instance.getBoundsBox();

        if (box == null)
            return;

        ArenaStructureSize size = box.size();

        messageManager.send(player, Message.ARENA_BOUNDS_SIZE,
                "id", instance.getId(),
                "sizeX", size.x(),
                "sizeY", size.y(),
                "sizeZ", size.z());

        warnAboutOpenings(messageManager, player, instance, box);
    }

    /**
     * Sent only when gaps are actually found, and only ever as advice.
     *
     * <p>A gap is not necessarily wrong - an arena with a deliberate doorway is
     * a legitimate design - so this never blocks the corner from being set. It
     * exists because the consequence is otherwise invisible until a match is
     * running: liquid that refuses to flow, and a wall that is never repaired.
     */
    /**
     * Advice, never a refusal. A bounds box with an opening still works and
     * still contains the duel; the only symptom is liquid that looks stuck, so
     * rejecting the corner would block a legitimate setup - an open-air arena
     * where nobody will ever use a bucket - to prevent a cosmetic oddity.
     */
    private static void warnAboutOpenings(MessageManager messageManager, Player player, ArenaInstance instance, BlockBox box)
    {
        ArenaBoundsValidator.OpeningReport report = ArenaBoundsValidator.inspect(box);

        if (!report.hasOpenings())
            return;

        messageManager.send(player, Message.ARENA_BOUNDS_OPENINGS,
                "id", instance.getId(),
                "openings", report.totalOpenings());
    }
}
