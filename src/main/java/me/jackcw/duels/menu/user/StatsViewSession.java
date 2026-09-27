package me.jackcw.duels.menu.user;

import me.jackcw.duels.stats.StatsPlayer;
import me.jackcw.duels.stats.StatsQuery;
import me.jackcw.duels.stats.StatsTimePeriod;

final class StatsViewSession
{
    private final StatsPlayer target;
    private StatsQuery filters;
    private StatsTimePeriod timePeriod = StatsTimePeriod.ALL_TIME;
    private int historyPage;
    private long revision;

    StatsViewSession(StatsPlayer target)
    {
        this.target = target;
        this.filters = StatsQuery.forPlayer(target.id());
    }

    StatsPlayer target() { return target; }
    StatsQuery filters() { return filters; }
    void filters(StatsQuery filters) { this.filters = filters; historyPage = 0; revision++; }
    StatsTimePeriod timePeriod() { return timePeriod; }
    void cycleTimePeriod() { timePeriod = timePeriod.next(); historyPage = 0; revision++; }
    StatsQuery query() { return timePeriod.apply(filters, System.currentTimeMillis()); }
    int historyPage() { return historyPage; }
    void historyPage(int value) { historyPage = Math.max(0, value); revision++; }
    long revision() { return revision; }

    void clear()
    {
        filters = StatsQuery.forPlayer(target.id());
        timePeriod = StatsTimePeriod.ALL_TIME;
        historyPage = 0;
        revision++;
    }
}
