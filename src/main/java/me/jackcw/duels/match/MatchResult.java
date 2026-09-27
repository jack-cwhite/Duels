package me.jackcw.duels.match;

import java.util.UUID;

public record MatchResult(
        int arenaId,
        UUID player1Id,
        String player1Name,
        UUID player2Id,
        String player2Name,
        UUID winnerId,
        Integer kitId1,
        Integer kitId2,
        long startedAt,
        Long combatStartedAt,
        long endedAt,
        MatchEndReason endReason,
        MatchState endedState,
        String damageCause)
{
}
