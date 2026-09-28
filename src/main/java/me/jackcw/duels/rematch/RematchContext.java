package me.jackcw.duels.rematch;

import java.time.Instant;
import java.util.UUID;

/**
 * The opportunity two players have, for a short window after a finished duel, to
 * play each other again on the same arena.
 *
 * <p>It holds identifiers and nothing else. Deliberately absent are {@code Match},
 * {@code ArenaInstance}, {@code Kit}, {@code Player} and inventory references:
 * the window outlives the match that created it, so keeping any of those alive
 * would pin a finished match's objects in memory and, worse, let a rematch be
 * accepted against an arena copy that has since been reset, released or given to
 * somebody else. Only the arena's configuration id is kept, and a fresh copy is
 * allocated normally when the rematch is accepted.
 *
 * <p>Names are kept alongside the ids purely so a message can address a player
 * who has since logged out without a blocking name lookup.
 */
public record RematchContext(
        UUID player1Id,
        String player1Name,
        UUID player2Id,
        String player2Name,
        int arenaId,
        Instant expiry)
{
    public boolean involves(UUID playerId)
    {
        return player1Id.equals(playerId) || player2Id.equals(playerId);
    }

    public UUID opponentOf(UUID playerId)
    {
        if (player1Id.equals(playerId))
            return player2Id;

        if (player2Id.equals(playerId))
            return player1Id;

        return null;
    }

    public String opponentNameOf(UUID playerId)
    {
        if (player1Id.equals(playerId))
            return player2Name;

        if (player2Id.equals(playerId))
            return player1Name;

        return null;
    }

    public boolean isExpired()
    {
        return !expiry.isAfter(Instant.now());
    }

    /**
     * Whole seconds left in the window, never negative.
     *
     * <p>Useful for display. Invitations use {@link #expiry()} itself so
     * rounding never cuts off the final fraction of a second.
     */
    public int remainingSeconds()
    {
        long remaining = expiry.getEpochSecond() - Instant.now().getEpochSecond();

        return remaining > 0 ? (int) remaining : 0;
    }
}
