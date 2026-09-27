package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.MatchResult;
import me.jackcw.jcore.storage.YamlRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class YamlStatsRepository implements StatsRepository
{
    private final YamlRepository<MatchRecord> repository;
    private final List<MatchRecord> matches = new ArrayList<>();
    private int nextId;

    public YamlStatsRepository(Duels plugin)
    {
        this.repository = new YamlRepository<>(
                plugin.core().files().yaml("stats.yml"),
                plugin.core().serializers(),
                "matches",
                MatchRecord.class,
                MatchRecord::getId
        );
        matches.addAll(repository.findAll());
        nextId = matches.stream().mapToInt(MatchRecord::getId).max().orElse(0) + 1;
    }

    @Override
    public CompletableFuture<Void> recordMatch(MatchResult result)
    {
        int id = nextId++;
        MatchRecord record = new MatchRecord(id, result.arenaId(), result.player1Id(), result.player1Name(),
                result.player2Id(), result.player2Name(), result.winnerId(), result.kitId1(), result.kitId2(),
                result.startedAt(), result.combatStartedAt(), result.endedAt(), result.endReason(),
                result.endedState(), result.damageCause());
        matches.add(record);
        repository.save(record);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<PlayerStats> getPlayerStats(StatsQuery query)
    {
        requirePlayer(query);
        return CompletableFuture.completedFuture(StatsAnalytics.summarize(filtered(query)));
    }

    @Override
    public CompletableFuture<List<MatchHistoryEntry>> getMatchHistory(StatsQuery query, int limit, int offset)
    {
        requirePlayer(query);
        List<MatchHistoryEntry> filtered = filtered(query).stream()
                .sorted(Comparator.comparingLong(MatchHistoryEntry::endedAt).reversed()
                        .thenComparing(Comparator.comparingLong(MatchHistoryEntry::matchId).reversed()))
                .toList();
        int start = Math.min(offset, filtered.size());
        int end = Math.min(start + limit, filtered.size());
        return CompletableFuture.completedFuture(List.copyOf(filtered.subList(start, end)));
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> getLeaderboard(StatsQuery query, LeaderboardMetric metric,
                                                                     int minimumMatches, int limit)
    {
        Map<UUID, List<MatchHistoryEntry>> byPlayer = new LinkedHashMap<>();
        for (MatchRecord match : matches)
        {
            addIfMatching(byPlayer, query, match.perspectiveFor(match.getPlayer1Id()));
            addIfMatching(byPlayer, query, match.perspectiveFor(match.getPlayer2Id()));
        }

        List<LeaderboardEntry> entries = byPlayer.values().stream()
                .map(history -> new LeaderboardEntry(history.getFirst().playerId(),
                        latestName(history), StatsAnalytics.summarize(history)))
                .filter(entry -> entry.value(metric) > 0)
                .filter(entry -> metric != LeaderboardMetric.WIN_RATE || entry.stats().matches() >= minimumMatches)
                .sorted(leaderboardComparator(metric))
                .limit(limit)
                .toList();
        return CompletableFuture.completedFuture(entries);
    }

    @Override
    public CompletableFuture<Optional<StatsPlayer>> findPlayer(String name)
    {
        for (int i = matches.size() - 1; i >= 0; i--)
        {
            MatchRecord match = matches.get(i);
            if (match.getPlayer1Name().equalsIgnoreCase(name))
                return CompletableFuture.completedFuture(Optional.of(new StatsPlayer(match.getPlayer1Id(), match.getPlayer1Name())));
            if (match.getPlayer2Name().equalsIgnoreCase(name))
                return CompletableFuture.completedFuture(Optional.of(new StatsPlayer(match.getPlayer2Id(), match.getPlayer2Name())));
        }
        return CompletableFuture.completedFuture(Optional.empty());
    }

    private List<MatchHistoryEntry> filtered(StatsQuery query)
    {
        List<MatchHistoryEntry> result = new ArrayList<>();
        for (MatchRecord match : matches)
        {
            MatchHistoryEntry perspective = match.perspectiveFor(query.playerId());
            if (perspective != null && matches(query, perspective))
                result.add(perspective);
        }
        return result;
    }

    private void addIfMatching(Map<UUID, List<MatchHistoryEntry>> grouped, StatsQuery query, MatchHistoryEntry entry)
    {
        if (matches(query, entry))
            grouped.computeIfAbsent(entry.playerId(), ignored -> new ArrayList<>()).add(entry);
    }

    static boolean matches(StatsQuery query, MatchHistoryEntry entry)
    {
        return (query.playerId() == null || query.playerId().equals(entry.playerId()))
                && (query.opponentId() == null || query.opponentId().equals(entry.opponentId()))
                && (query.playerKitId() == null || query.playerKitId().equals(entry.playerKitId()))
                && (query.opponentKitId() == null || query.opponentKitId().equals(entry.opponentKitId()))
                && (query.arenaId() == null || query.arenaId() == entry.arenaId())
                && (query.endedAfter() == null || entry.endedAt() >= query.endedAfter())
                && (query.endedBefore() == null || entry.endedAt() < query.endedBefore());
    }

    static Comparator<LeaderboardEntry> leaderboardComparator(LeaderboardMetric metric)
    {
        return Comparator.comparingDouble((LeaderboardEntry entry) -> entry.value(metric)).reversed()
                .thenComparing(Comparator.comparingInt((LeaderboardEntry entry) -> entry.stats().matches()).reversed())
                .thenComparing(LeaderboardEntry::playerName, String.CASE_INSENSITIVE_ORDER);
    }

    private static String latestName(List<MatchHistoryEntry> history)
    {
        return history.stream().max(Comparator.comparingLong(MatchHistoryEntry::endedAt))
                .map(MatchHistoryEntry::playerName).orElse("Unknown");
    }

    private static void requirePlayer(StatsQuery query)
    {
        if (query.playerId() == null)
            throw new IllegalArgumentException("A player is required for profile statistics");
    }
}
