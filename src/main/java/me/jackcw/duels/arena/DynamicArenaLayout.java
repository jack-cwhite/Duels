package me.jackcw.duels.arena;

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
}
