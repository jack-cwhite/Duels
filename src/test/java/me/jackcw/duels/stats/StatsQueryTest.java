package me.jackcw.duels.stats;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

class StatsQueryTest
{
    @Test
    void playerCannotBeTheirOwnOpponent()
    {
        UUID player = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> StatsQuery.forPlayer(player).withOpponent(player));
    }
}
