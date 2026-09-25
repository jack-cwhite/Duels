package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.MatchResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The plugin's entry point to stats, and the place that orders reads against
 * writes.
 *
 * <p>The ordering is the reason this class holds state at all. Writes and reads
 * both run on JCore's shared async executor, which is a fixed thread pool, so
 * two tasks submitted one after the other can run in either order or at the
 * same time. A duel ending submits a write; the winner immediately running
 * {@code /duels stats} submits a read; and the read could legitimately finish
 * first and report the score from before the match they just won. Nothing was
 * lost - the write landed a moment later - but the player saw a stale number
 * and had no way to tell it apart from a bug.
 *
 * <p>So each read is chained behind the writes outstanding at the time it is
 * requested. That is weaker than a transaction and deliberately so: it only
 * promises that a read issued after a write sees it, which is exactly the
 * guarantee the "I just won" case needs, and it costs nothing when no write is
 * in flight because the chain is an already-completed future.
 */
public final class StatsManager
{
    private final StatsRepository repository;

    // Only ever touched from the main server thread, where both match end and
    // every command and menu that reads stats run, so this needs no
    // synchronisation. The futures it chains do the cross-thread work.
    private CompletableFuture<Void> pendingWrites = CompletableFuture.completedFuture(null);

    public StatsManager(Duels plugin)
    {
        this.repository = switch (plugin.getSettings().statsStorage())
        {
            case SQL -> new SqlStatsRepository(plugin);
            case YAML -> new YamlStatsRepository(plugin);
        };
    }

    /**
     * Records a finished match, returning a handle that completes once the
     * result is durably stored.
     *
     * <p>{@code MatchManager} ignores the handle - a stats failure must not stop
     * an arena being released or players being restored - but it has to exist,
     * because without it there is no way for anything, including a test, to know
     * the write happened.
     */
    public CompletableFuture<Void> recordMatch(MatchResult result)
    {
        CompletableFuture<Void> write = repository.recordMatch(
            result.arenaId(),
            result.player1Id(),
            result.player2Id(),
            result.winnerId(),
            result.kitId1(),
            result.kitId2(),
            result.endedAt());

        // A failed write must not poison the chain and leave every later read
        // failing too, so the ordering link swallows the failure. The write's
        // own future still carries it, and the repository already logs it.
        pendingWrites = CompletableFuture.allOf(pendingWrites, write)
                .exceptionally(throwable -> null);

        return write;
    }

    public CompletableFuture<Integer> getWins(UUID playerId)
    {
        return afterPendingWrites(() -> repository.getWins(playerId));
    }

    public CompletableFuture<Integer> getLosses(UUID playerId)
    {
        return afterPendingWrites(() -> repository.getLosses(playerId));
    }

    public CompletableFuture<List<LeaderboardEntry>> getTopPlayers(int limit)
    {
        return afterPendingWrites(() -> repository.getTopPlayers(limit));
    }

    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB)
    {
        return getHeadToHead(playerA, playerB, null, null, null);
    }

    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB, Integer kitIdA, Integer kitIdB, Integer arenaId)
    {
        return afterPendingWrites(() -> repository.getHeadToHead(playerA, playerB, kitIdA, kitIdB, arenaId));
    }

    /**
     * The query is passed as a supplier rather than called eagerly, because
     * calling it is what submits it to the executor - doing that before the
     * chain would be the race this exists to prevent.
     */
    private <T> CompletableFuture<T> afterPendingWrites(Supplier<CompletableFuture<T>> query)
    {
        return pendingWrites.thenCompose(ignored -> query.get());
    }
}
