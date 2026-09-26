package me.jackcw.duels.arena;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicArenaLayoutTest
{
    @Test
    void slotsUseDeterministicPaddedGridCoordinates()
    {
        DynamicArenaLayout layout = new DynamicArenaLayout(1, "duels_dynamic", null, 64, 8, 256, 256, 16, 64);

        DynamicArenaSlot first = layout.slot(0);
        DynamicArenaSlot nextColumn = layout.slot(1);
        DynamicArenaSlot nextRow = layout.slot(8);

        assertEquals(16, first.originX());
        assertEquals(16, first.originZ());
        assertEquals(304, nextColumn.originX());
        assertEquals(16, nextColumn.originZ());
        assertEquals(16, nextRow.originX());
        assertEquals(304, nextRow.originZ());
    }

    @Test
    void slotFitChecksProtectThePaddingBoundary()
    {
        DynamicArenaSlot slot = new DynamicArenaLayout(1, "duels_dynamic", null, 1, 1, 100, 90, 16, 64).slot(0);

        assertTrue(slot.fits(new ArenaStructureSize(100, 1000, 90)));
        assertFalse(slot.fits(new ArenaStructureSize(101, 10, 90)));
        assertFalse(slot.fits(new ArenaStructureSize(100, 10, 91)));
    }

    @Test
    void visibilitySeparationUsesTheRealTemplateFootprint()
    {
        DynamicArenaLayout layout = new DynamicArenaLayout(1, "duels_dynamic", null, 64, 8, 256, 256, 16, 64);

        assertEquals(32, layout.minimumAdjacentSeparation(new ArenaStructureSize(256, 20, 256)).orElseThrow());
        assertEquals(238, layout.minimumAdjacentSeparation(new ArenaStructureSize(50, 20, 50)).orElseThrow());
        assertTrue(layout.mayExposeAdjacentSlot(new ArenaStructureSize(256, 20, 256), 2));
        assertFalse(layout.mayExposeAdjacentSlot(new ArenaStructureSize(50, 20, 50), 10));
    }

    @Test
    void aSingleSlotLayoutHasNoAdjacentVisibilityRisk()
    {
        DynamicArenaLayout layout = new DynamicArenaLayout(1, "duels_dynamic", null, 1, 1, 256, 256, 16, 64);

        assertTrue(layout.minimumAdjacentSeparation(new ArenaStructureSize(256, 20, 256)).isEmpty());
        assertFalse(layout.mayExposeAdjacentSlot(new ArenaStructureSize(256, 20, 256), 32));
    }
}
