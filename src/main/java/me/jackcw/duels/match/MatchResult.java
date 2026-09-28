package me.jackcw.duels.match;

import java.util.UUID;

/**
 * One finished match, as every consumer of a result sees it.
 *
 * @param resultId identity for this result, generated once when the match ends.
 *                 Everything downstream keys off it: it is what lets the stats
 *                 store reject a duplicate insert and what lets a reward be tied
 *                 to the match it was paid for. Note it identifies the
 *                 <em>result</em>, not the match - a match that never ends
 *                 produces no result and so has no id.
 */
public record MatchResult(
        UUID resultId,
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
