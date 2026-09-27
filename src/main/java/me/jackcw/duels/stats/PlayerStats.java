package me.jackcw.duels.stats;

public record PlayerStats(
        int matches,
        int wins,
        int losses,
        int disconnectLosses,
        int currentStreak,
        int bestStreak,
        int timedMatches,
        Long averageCombatDurationMillis)
{
    public double winRate()
    {
        return matches == 0 ? 0.0 : wins * 100.0 / matches;
    }
}
