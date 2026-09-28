package me.jackcw.duels.arena;

import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class VoidArenaChunkGeneratorTest
{
    private ServerMock server;

    @BeforeEach
    void setup()
    {
        server = MockBukkit.mock();
    }

    @AfterEach
    void teardown()
    {
        MockBukkit.unmock();
    }

    /**
     * The assertion that matters is "not null". A null fixed spawn hands the
     * server its own spawn search, which cannot terminate cheaply in a world
     * with no blocks in it and stalled the main thread long enough for Paper's
     * watchdog to fire. Anyone tidying this override away reintroduces that.
     */
    @Test
    void aFixedSpawnIsProvidedSoTheServerNeverSearchesAnEmptyWorldForOne()
    {
        WorldMock world = server.addSimpleWorld("void_arena_world");

        Location spawn = new VoidArenaChunkGenerator().getFixedSpawnLocation(world, new Random());

        assertNotNull(spawn, "an empty world must name its own spawn rather than leaving the server to find one");
        assertEquals(world, spawn.getWorld());
    }
}
