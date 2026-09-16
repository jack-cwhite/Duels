package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.jcore.storage.YamlRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class YamlStatsRepository implements StatsRepository
{
    private final YamlRepository<MatchRecord> repository;
    private final List<MatchRecord> matches = new ArrayList<>();

    public YamlStatsRepository(Duels plugin)
    {
        this.repository = new YamlRepository<>(
                plugin.getJCore().files().yaml("stats.yml"),
                plugin.getJCore().serializers(),
                "matches",
                MatchRecord.class,
                MatchRecord::getId
        );

        matches.addAll(repository.findAll());
    }

    @Override
    public void recordMatch(int arenaId, UUID player1Id, UUID player2Id, UUID winnerId, Integer kitId1, Integer kitId2, long endedAt)
    {
        int id = matches.size() + 1;

        while (hasId(id))
            id++;

        MatchRecord record = new MatchRecord(id, arenaId, player1Id, player2Id, winnerId, kitId1, kitId2, endedAt);

        matches.add(record);
        repository.save(record);
    }

    @Override
    public CompletableFuture<Integer> getWins(UUID playerId)
    {
        int wins = (int) matches.stream().filter(match -> match.won(playerId)).count();

        return CompletableFuture.completedFuture(wins);
    }

    @Override
    public CompletableFuture<Integer> getLosses(UUID playerId)
    {
        int losses = (int) matches.stream()
                .filter(match -> match.involves(playerId) && !match.won(playerId))
                .count();

        return CompletableFuture.completedFuture(losses);
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> getTopPlayers(int limit)
    {
        Map<UUID, Integer> wins = new HashMap<>();

        for (MatchRecord match : matches)
            wins.merge(match.getWinnerId(), 1, Integer::sum);

        List<LeaderboardEntry> entries = new ArrayList<>();

        for (Map.Entry<UUID, Integer> entry : wins.entrySet())
            entries.add(new LeaderboardEntry(entry.getKey(), entry.getValue()));

        entries.sort(Comparator.comparingInt(LeaderboardEntry::wins).reversed());

        return CompletableFuture.completedFuture(
                entries.size() > limit ? entries.subList(0, limit) : entries
        );
    }

    @Override
    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB, Integer kitIdA, Integer kitIdB, Integer arenaId)
    {
        int winsA = 0;
        int winsB = 0;

        for (MatchRecord match : matches)
        {
            if (!match.involves(playerA) || !match.involves(playerB))
                continue;

            if (arenaId != null && match.getArenaId() != arenaId)
                continue;

            if (kitIdA != null && !kitIdA.equals(match.kitIdFor(playerA)))
                continue;

            if (kitIdB != null && !kitIdB.equals(match.kitIdFor(playerB)))
                continue;

            if (match.won(playerA))
                winsA++;
            else
                winsB++;
        }

        return CompletableFuture.completedFuture(new HeadToHead(playerA, playerB, winsA, winsB));
    }

    private boolean hasId(int id)
    {
        for (MatchRecord match : matches)
            if (match.getId() == id)
                return true;

        return false;
    }
}
