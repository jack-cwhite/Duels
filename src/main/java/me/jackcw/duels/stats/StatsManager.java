package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.MatchResult;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Orders every stats read behind all writes that were pending when it was requested. */
public final class StatsManager
{
    private final StatsRepository repository;
    private final int winRateMinimumMatches;
    private CompletableFuture<Void> pendingWrites = CompletableFuture.completedFuture(null);

    public StatsManager(Duels plugin)
    {
        repository = switch (plugin.getSettings().statsStorage())
        {
            case SQL -> new SqlStatsRepository(plugin);
            case YAML -> new YamlStatsRepository(plugin);
        };
        winRateMinimumMatches = plugin.getSettings().statsWinRateMinimumMatches();
    }

    public CompletableFuture<Void> recordMatch(MatchResult result)
    {
        CompletableFuture<Void> write = repository.recordMatch(result);
        pendingWrites = CompletableFuture.allOf(pendingWrites, write).exceptionally(throwable -> null);
        return write;
    }

    public CompletableFuture<PlayerStats> getPlayerStats(StatsQuery query)
    {
        return afterPendingWrites(() -> repository.getPlayerStats(query));
    }

    public CompletableFuture<List<MatchHistoryEntry>> getMatchHistory(StatsQuery query, int limit, int offset)
    {
        return afterPendingWrites(() -> repository.getMatchHistory(query, limit, offset));
    }

    public CompletableFuture<List<LeaderboardEntry>> getLeaderboard(StatsQuery query, LeaderboardMetric metric, int limit)
    {
        return afterPendingWrites(() -> repository.getLeaderboard(query, metric, winRateMinimumMatches, limit));
    }

    public CompletableFuture<Optional<StatsPlayer>> findPlayer(String name)
    {
        return afterPendingWrites(() -> repository.findPlayer(name));
    }

    public int getWinRateMinimumMatches()
    {
        return winRateMinimumMatches;
    }

    public void validateStorage()
    {
        if (repository instanceof SqlStatsRepository sql)
            sql.validateSchema();
    }

    // Compatibility conveniences for callers that only need the old totals.
    public CompletableFuture<Integer> getWins(UUID playerId)
    {
        return getPlayerStats(StatsQuery.forPlayer(playerId)).thenApply(PlayerStats::wins);
    }

    public CompletableFuture<Integer> getLosses(UUID playerId)
    {
        return getPlayerStats(StatsQuery.forPlayer(playerId)).thenApply(PlayerStats::losses);
    }

    public CompletableFuture<List<LeaderboardEntry>> getTopPlayers(int limit)
    {
        return getLeaderboard(StatsQuery.leaderboard(), LeaderboardMetric.WINS, limit);
    }

    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB, Integer kitIdA,
                                                       Integer kitIdB, Integer arenaId)
    {
        StatsQuery query = new StatsQuery(playerA, playerB, kitIdA, kitIdB, arenaId, null, null);
        return getPlayerStats(query).thenApply(stats -> new HeadToHead(playerA, playerB, stats.wins(), stats.losses()));
    }

    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB)
    {
        return getHeadToHead(playerA, playerB, null, null, null);
    }

    private <T> CompletableFuture<T> afterPendingWrites(Supplier<CompletableFuture<T>> query)
    {
        return pendingWrites.thenCompose(ignored -> query.get());
    }
}
