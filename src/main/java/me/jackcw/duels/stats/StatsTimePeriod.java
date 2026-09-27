package me.jackcw.duels.stats;

import java.time.Duration;

public enum StatsTimePeriod
{
    ALL_TIME("All Time", null),
    LAST_7_DAYS("Last 7 Days", Duration.ofDays(7)),
    LAST_30_DAYS("Last 30 Days", Duration.ofDays(30));

    private final String displayName;
    private final Duration duration;

    StatsTimePeriod(String displayName, Duration duration)
    {
        this.displayName = displayName;
        this.duration = duration;
    }

    public String displayName()
    {
        return displayName;
    }

    public StatsQuery apply(StatsQuery query, long now)
    {
        return query.withTimeRange(duration == null ? null : now - duration.toMillis(), null);
    }

    public StatsTimePeriod next()
    {
        StatsTimePeriod[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
