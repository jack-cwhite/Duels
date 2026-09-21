package me.jackcw.duels.arena;

/**
 * Validated settings for Duels-owned dynamic arena slots. These values become
 * immutable in {@code dynamic-layout.yml} on first use so a later config edit
 * cannot silently move an already-provisioned arena.
 */
public record DynamicArenaSettings(
        String worldName,
        int maxSlots,
        int slotsPerRow,
        int slotWidth,
        int slotLength,
        int slotPadding,
        int baseY,
        long maxTemplateVolume,
        int provisionTimeoutSeconds,
        int cleanupBlocksPerTick
)
{
    public DynamicArenaSettings
    {
        if (worldName == null || worldName.isBlank())
            throw new IllegalArgumentException("Dynamic arena world name cannot be blank");
        if (maxSlots < 1 || slotsPerRow < 1 || slotWidth < 1 || slotLength < 1 || slotPadding < 0
                || maxTemplateVolume < 1 || provisionTimeoutSeconds < 1 || cleanupBlocksPerTick < 1)
            throw new IllegalArgumentException("Dynamic arena settings must have valid positive capacities");
    }
}
