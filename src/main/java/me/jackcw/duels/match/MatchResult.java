package me.jackcw.duels.match;

import java.util.UUID;

public record MatchResult(int arenaId, UUID player1Id, UUID player2Id, UUID winnerId, Integer kitId1, Integer kitId2, long endedAt)
{
}