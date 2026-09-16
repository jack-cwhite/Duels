package me.jackcw.duels.stats;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StatsRepository
{
    void recordMatch(int arenaId, UUID player1Id, UUID player2Id, UUID winnerId, Integer kitId1, Integer kitId2, long endedAt);

    CompletableFuture<Integer> getWins(UUID playerId);

    CompletableFuture<Integer> getLosses(UUID playerId);

    CompletableFuture<List<LeaderboardEntry>> getTopPlayers(int limit);

    CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB, Integer kitIdA, Integer kitIdB, Integer arenaId);
}
