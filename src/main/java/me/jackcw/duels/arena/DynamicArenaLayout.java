package me.jackcw.duels.arena;

import java.util.OptionalInt;
import java.util.UUID;

/** Immutable geometry of the Duels-owned dynamic arena world. */
public record DynamicArenaLayout(
        int version, String worldName, UUID worldId, int maxSlots, int slotsPerRow,
        int slotWidth, int slotLength, int slotPadding, int baseY
)
{
    public static final int CURRENT_VERSION = 1;

    public DynamicArenaLayout
    {
        if (version != CURRENT_VERSION)
            throw new IllegalArgumentException("Unsupported dynamic arena layout version " + version);
        if (worldName == null || worldName.isBlank() || maxSlots < 1 || slotsPerRow < 1 || slotWidth < 1
                || slotLength < 1 || slotPadding < 0)
            throw new IllegalArgumentException("Invalid dynamic arena layout");
    }

    public static DynamicArenaLayout fromSettings(DynamicArenaSettings settings)
    {
        return new DynamicArenaLayout(CURRENT_VERSION, settings.worldName(), null, settings.maxSlots(), settings.slotsPerRow(),
                settings.slotWidth(), settings.slotLength(), settings.slotPadding(), settings.baseY());
    }

    public DynamicArenaLayout withWorldId(UUID worldId)
    {
        return new DynamicArenaLayout(version, worldName, worldId, maxSlots, slotsPerRow, slotWidth, slotLength, slotPadding, baseY);
    }

    public DynamicArenaSlot slot(int index)
    {
        if (index < 0 || index >= maxSlots)
            throw new IllegalArgumentException("Slot index " + index + " is outside this layout");

        int column = index % slotsPerRow;
        int row = index / slotsPerRow;
        return new DynamicArenaSlot(index, column * (slotWidth + slotPadding * 2) + slotPadding, baseY,
                row * (slotLength + slotPadding * 2) + slotPadding, slotWidth, slotLength);
    }

    /**
     * Smallest edge-to-edge gap from a copy of this size to the next usable
     * grid slot, considering only directions in which this layout actually has
     * another slot.
     *
     * <p>The structure is pasted at the slot origin rather than centred, so its
     * own width/length matters. Using only {@code slotPadding * 2} would assume
     * every template fills its slot and would warn for ordinary small arenas
     * that actually have hundreds of blocks of empty space around them.
     */
    public OptionalInt minimumAdjacentSeparation(ArenaStructureSize size)
    {
        if (size == null || size.x() > slotWidth || size.z() > slotLength)
            throw new IllegalArgumentException("Structure size must fit this dynamic arena layout");

        int minimum = Integer.MAX_VALUE;
        int columns = Math.min(maxSlots, slotsPerRow);

        if (columns > 1)
            minimum = Math.min(minimum, slotWidth + slotPadding * 2 - size.x());
        if (maxSlots > slotsPerRow)
            minimum = Math.min(minimum, slotLength + slotPadding * 2 - size.z());

        return minimum == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(minimum);
    }

    public boolean mayExposeAdjacentSlot(ArenaStructureSize size, int sendDistanceChunks)
    {
        if (sendDistanceChunks < 0)
            throw new IllegalArgumentException("Send distance cannot be negative");

        OptionalInt separation = minimumAdjacentSeparation(size);
        return separation.isPresent() && separation.getAsInt() <= sendDistanceChunks * 16;
    }
}
