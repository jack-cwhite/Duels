package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchState;

import java.util.UUID;

/** A completed match expressed from one viewed player's perspective. */
public record MatchHistoryEntry(
        long matchId,
        UUID playerId,
        String playerName,
        UUID opponentId,
        String opponentName,
        boolean won,
        int arenaId,
        Integer playerKitId,
        Integer opponentKitId,
        long startedAt,
        Long combatStartedAt,
        long endedAt,
        MatchEndReason endReason,
        MatchState endedState,
        String damageCause)
{
    public Long combatDurationMillis()
    {
        return combatStartedAt == null ? null : Math.max(0L, endedAt - combatStartedAt);
    }

    public long sessionDurationMillis()
    {
        return Math.max(0L, endedAt - startedAt);
    }
}
