package me.jackcw.duels.stats;

import java.util.UUID;

public record LeaderboardEntry(UUID playerId, int wins)
{
}
