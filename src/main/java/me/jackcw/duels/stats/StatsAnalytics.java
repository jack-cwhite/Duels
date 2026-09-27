package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchEndReason;

import java.util.Comparator;
import java.util.List;

final class StatsAnalytics
{
    private StatsAnalytics()
    {
    }

    static PlayerStats summarize(List<MatchHistoryEntry> unsorted)
    {
        List<MatchHistoryEntry> matches = unsorted.stream()
                .sorted(Comparator.comparingLong(MatchHistoryEntry::endedAt)
                        .thenComparingLong(MatchHistoryEntry::matchId))
                .toList();

        int wins = 0;
        int disconnectLosses = 0;
        int currentStreak = 0;
        int bestStreak = 0;
        int timedMatches = 0;
        long totalCombatDuration = 0L;

        for (MatchHistoryEntry match : matches)
        {
            if (match.won())
            {
                wins++;
                currentStreak++;
                bestStreak = Math.max(bestStreak, currentStreak);
            }
            else
            {
                currentStreak = 0;
                if (match.endReason() == MatchEndReason.DISCONNECT)
                    disconnectLosses++;
            }

            Long duration = match.combatDurationMillis();
            if (duration != null)
            {
                timedMatches++;
                totalCombatDuration += duration;
            }
        }

        Long average = timedMatches == 0 ? null : totalCombatDuration / timedMatches;
        return new PlayerStats(matches.size(), wins, matches.size() - wins, disconnectLosses,
                currentStreak, bestStreak, timedMatches, average);
    }
}
