package me.jackcw.duels.stats;

import java.util.UUID;

/**
 * One composable definition of what a statistics screen is asking about.
 * A null filter means "any". Kit filters are deliberately relative to the
 * viewed player, so mixed-kit matches remain unambiguous.
 */
public record StatsQuery(
        UUID playerId,
        UUID opponentId,
        Integer playerKitId,
        Integer opponentKitId,
        Integer arenaId,
        Long endedAfter,
        Long endedBefore)
{
    public StatsQuery
    {
        if (playerId != null && playerId.equals(opponentId))
            throw new IllegalArgumentException("A player cannot be their own statistics opponent");
    }

    public static StatsQuery forPlayer(UUID playerId)
    {
        return new StatsQuery(playerId, null, null, null, null, null, null);
    }

    public static StatsQuery leaderboard()
    {
        return new StatsQuery(null, null, null, null, null, null, null);
    }

    public StatsQuery withOpponent(UUID value)
    {
        return new StatsQuery(playerId, value, playerKitId, opponentKitId, arenaId, endedAfter, endedBefore);
    }

    public StatsQuery withPlayerKit(Integer value)
    {
        return new StatsQuery(playerId, opponentId, value, opponentKitId, arenaId, endedAfter, endedBefore);
    }

    public StatsQuery withOpponentKit(Integer value)
    {
        return new StatsQuery(playerId, opponentId, playerKitId, value, arenaId, endedAfter, endedBefore);
    }

    public StatsQuery withArena(Integer value)
    {
        return new StatsQuery(playerId, opponentId, playerKitId, opponentKitId, value, endedAfter, endedBefore);
    }

    public StatsQuery withTimeRange(Long after, Long before)
    {
        return new StatsQuery(playerId, opponentId, playerKitId, opponentKitId, arenaId, after, before);
    }

    public StatsQuery withoutFilters()
    {
        return forPlayer(playerId);
    }
}
