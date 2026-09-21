package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.SerializerManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArenaSerializerTest
{
    @Test
    void unknownFieldsAreRejectedInsteadOfSilentlyDiscarded()
    {
        ArenaSerializer serializer = new ArenaSerializer(new SerializerManager());

        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(2, Map.of(
                "name", "Mistyped arena",
                "spawn3", Map.of("world", "world")
        )));
    }

    @Test
    void existingArenaWithoutProvisioningModeDefaultsToStatic()
    {
        ArenaSerializer serializer = new ArenaSerializer(new SerializerManager());

        Arena arena = serializer.deserialize(3, Map.of("name", "Legacy arena"));

        assertEquals(ArenaProvisioningMode.STATIC, arena.getProvisioningMode());
    }

    @Test
    void dynamicProvisioningModeRoundTrips()
    {
        ArenaSerializer serializer = new ArenaSerializer(new SerializerManager());
        Arena arena = new Arena(4, "Dynamic arena");
        arena.setProvisioningMode(ArenaProvisioningMode.DYNAMIC);

        Object serialized = serializer.serialize(arena);
        Arena restored = serializer.deserialize(4, serialized);

        assertEquals(ArenaProvisioningMode.DYNAMIC, restored.getProvisioningMode());
    }
}
