package me.jackcw.duels.stats;

public enum LeaderboardMetric
{
    WINS("Most Wins"),
    MATCHES("Most Matches"),
    WIN_RATE("Highest Win Rate"),
    BEST_STREAK("Best Streak"),
    CURRENT_STREAK("Current Streak");

    private final String displayName;

    LeaderboardMetric(String displayName)
    {
        this.displayName = displayName;
    }

    public String displayName()
    {
        return displayName;
    }

    public LeaderboardMetric next()
    {
        LeaderboardMetric[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
