package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchResult;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface StatsRepository
{
    CompletableFuture<Void> recordMatch(MatchResult result);

    CompletableFuture<PlayerStats> getPlayerStats(StatsQuery query);

    CompletableFuture<List<MatchHistoryEntry>> getMatchHistory(StatsQuery query, int limit, int offset);

    CompletableFuture<List<LeaderboardEntry>> getLeaderboard(StatsQuery query, LeaderboardMetric metric,
                                                              int minimumMatches, int limit);

    CompletableFuture<Optional<StatsPlayer>> findPlayer(String name);
}
