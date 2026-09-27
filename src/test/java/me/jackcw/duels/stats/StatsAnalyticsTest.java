package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatsAnalyticsTest
{
    private final UUID player = UUID.randomUUID();
    private final UUID opponent = UUID.randomUUID();

    @Test
    void computesStreaksDisconnectsAndOnlyRealCombatDurations()
    {
        PlayerStats stats = StatsAnalytics.summarize(List.of(
                entry(1, true, 1_000L, 3_000L, MatchEndReason.DEFEAT),
                entry(2, true, 4_000L, 8_000L, MatchEndReason.DEFEAT),
                entry(3, false, null, 10_000L, MatchEndReason.DISCONNECT),
                entry(4, true, 12_000L, 14_000L, MatchEndReason.DEFEAT)
        ));

        assertEquals(4, stats.matches());
        assertEquals(3, stats.wins());
        assertEquals(1, stats.losses());
        assertEquals(1, stats.disconnectLosses());
        assertEquals(1, stats.currentStreak());
        assertEquals(2, stats.bestStreak());
        assertEquals(3, stats.timedMatches());
        assertEquals(2_666L, stats.averageCombatDurationMillis());
    }

    @Test
    void noCombatIsUnknownRatherThanZero()
    {
        PlayerStats stats = StatsAnalytics.summarize(List.of(
                entry(1, false, null, 2_000L, MatchEndReason.DISCONNECT)));

        assertEquals(0, stats.timedMatches());
        assertNull(stats.averageCombatDurationMillis());
    }

    private MatchHistoryEntry entry(long id, boolean won, Long combatStartedAt, long endedAt, MatchEndReason reason)
    {
        return new MatchHistoryEntry(id, player, "Player", opponent, "Opponent", won, 2,
                4, 7, 500L, combatStartedAt, endedAt, reason,
                combatStartedAt == null ? MatchState.PREGAME : MatchState.IN_PROGRESS,
                reason == MatchEndReason.DEFEAT ? "ENTITY_ATTACK" : null);
    }
}
