package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.SerializerManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArenaInstanceSerializerTest
{
    @Test
    void oldInstanceWithoutOriginDefaultsToManual()
    {
        ArenaInstance instance = new ArenaInstanceSerializer(new SerializerManager()).deserialize(2, Map.of("arenaId", 1));
        assertEquals(ArenaInstanceOrigin.MANUAL, instance.getOrigin());
    }

    @Test
    void provisionedInstanceRoundTripsItsRecoveryMetadata()
    {
        ArenaInstanceSerializer serializer = new ArenaInstanceSerializer(new SerializerManager());
        ArenaInstance instance = ArenaInstance.provisioned(2, 1, 7, 3, new ArenaStructureSize(100, 40, 80));

        ArenaInstance restored = serializer.deserialize(2, serializer.serialize(instance));

        assertEquals(ArenaInstanceOrigin.PROVISIONED, restored.getOrigin());
        assertEquals(7, restored.getDynamicSlotIndex());
        assertEquals(3, restored.getTemplateRevision());
        assertEquals(DynamicArenaState.PROVISIONING, restored.getDynamicState());
    }

    @Test
    void manualInstanceRejectsUnexpectedDynamicMetadata()
    {
        ArenaInstanceSerializer serializer = new ArenaInstanceSerializer(new SerializerManager());
        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(2, Map.of("arenaId", 1, "dynamic", Map.of())));
    }

    @Test
    void nonPlayableSourceRoundTripsWithoutGeneratedMetadata()
    {
        ArenaInstanceSerializer serializer = new ArenaInstanceSerializer(new SerializerManager());
        ArenaInstance source = new ArenaInstance(8, 3);
        source.markSource();

        ArenaInstance restored = serializer.deserialize(8, serializer.serialize(source));

        assertEquals(ArenaInstanceOrigin.SOURCE, restored.getOrigin());
        assertEquals(null, restored.getDynamicSlotIndex());
    }
}
