package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.SerializerManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
