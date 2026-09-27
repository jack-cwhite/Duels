package me.jackcw.duels.stats;

import java.util.UUID;

public record LeaderboardEntry(UUID playerId, String playerName, PlayerStats stats)
{
    public int wins()
    {
        return stats.wins();
    }

    public double value(LeaderboardMetric metric)
    {
        return switch (metric)
        {
            case WINS -> stats.wins();
            case MATCHES -> stats.matches();
            case WIN_RATE -> stats.winRate();
            case BEST_STREAK -> stats.bestStreak();
            case CURRENT_STREAK -> stats.currentStreak();
        };
    }
}
