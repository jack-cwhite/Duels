package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class StatsManager
{
    private final StatsRepository repository;

    public StatsManager(Duels plugin)
    {
        this.repository = switch (plugin.getSettings().statsStorage())
        {
            case SQL -> new SqlStatsRepository(plugin);
            case YAML -> new YamlStatsRepository(plugin);
        };
    }

    public void recordMatch(Match match, UUID winnerId, Integer kitId1, Integer kitId2)
    {
        repository.recordMatch(
                match.getArena().getId(),
                match.getPlayer1Id(),
                match.getPlayer2Id(),
                winnerId,
                kitId1,
                kitId2,
                System.currentTimeMillis()
        );
    }

    public CompletableFuture<Integer> getWins(UUID playerId)
    {
        return repository.getWins(playerId);
    }

    public CompletableFuture<Integer> getLosses(UUID playerId)
    {
        return repository.getLosses(playerId);
    }

    public CompletableFuture<List<LeaderboardEntry>> getTopPlayers(int limit)
    {
        return repository.getTopPlayers(limit);
    }

    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB)
    {
        return repository.getHeadToHead(playerA, playerB, null, null, null);
    }

    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB, Integer kitIdA, Integer kitIdB, Integer arenaId)
    {
        return repository.getHeadToHead(playerA, playerB, kitIdA, kitIdB, arenaId);
    }
}
