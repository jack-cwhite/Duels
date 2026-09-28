package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchResult;
import me.jackcw.duels.match.MatchResultConsumer;

/**
 * Records a finished match in the stats store.
 *
 * <p>This carries no logic of its own on purpose. It exists so that stats are one
 * consumer of a match result among several rather than the privileged thing
 * {@code endMatch} knows about directly, which is what lets rewards and later
 * ratings be added without touching the match lifecycle again.
 */
public final class StatsResultConsumer implements MatchResultConsumer
{
    private final StatsManager statsManager;

    public StatsResultConsumer(StatsManager statsManager)
    {
        this.statsManager = statsManager;
    }

    @Override
    public String name()
    {
        return "stats";
    }

    @Override
    public void accept(MatchResult result)
    {
        // The returned future is deliberately dropped. The repository already logs
        // the full result at SEVERE if the write fails, which is the only recovery
        // path stats offer, and waiting on it here would block the main thread
        // mid-cleanup.
        statsManager.recordMatch(result);
    }
}
