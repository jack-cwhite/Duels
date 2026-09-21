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

    @Test
    void dynamicTemplateRoundTripsWithRelativeCoordinates()
    {
        ArenaSerializer serializer = new ArenaSerializer(new SerializerManager());
        Arena arena = new Arena(5, "Castle");
        arena.setProvisioningMode(ArenaProvisioningMode.DYNAMIC);
        arena.setTemplateDefinition(new ArenaTemplateDefinition(
                2, "paper-nbt", "arena-5-r2.nbt", new ArenaStructureSize(100, 40, 90),
                new RelativeArenaLocation(4, 5, 6, 90.0F, 10.0F),
                new RelativeArenaLocation(80, 5, 70, -90.0F, 10.0F),
                new RelativeBlockPosition(2, 2, 2), new RelativeBlockPosition(90, 30, 80)
        ));

        Arena restored = serializer.deserialize(5, serializer.serialize(arena));

        assertEquals(ArenaTemplateStatus.READY, restored.getTemplateStatus());
        assertEquals("arena-5-r2.nbt", restored.getTemplateDefinition().fileName());
        assertEquals(90.0F, restored.getTemplateDefinition().spawn1().yaw());
        assertEquals(360_000L, restored.getTemplateDefinition().size().volume());
    }

    @Test
    void malformedTemplateFieldsAreRejectedInsteadOfSilentlyDiscarded()
    {
        ArenaSerializer serializer = new ArenaSerializer(new SerializerManager());

        assertThrows(IllegalArgumentException.class, () -> serializer.deserialize(6, Map.of(
                "name", "Castle",
                "template", Map.of("revision", 1, "unexpected", true)
        )));
    }
}
