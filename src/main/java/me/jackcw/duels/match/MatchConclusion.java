package me.jackcw.duels.match;

import java.util.Objects;
import java.util.UUID;

/**
 * The facts supplied by the gameplay rule that ended a match.
 *
 * <p>{@code damageCause} is the stable enum name exposed by Paper, such as
 * {@code ENTITY_ATTACK}, {@code PROJECTILE}, or {@code LAVA}. It is absent for
 * non-damage conclusions such as a disconnect.
 */
public record MatchConclusion(UUID winnerId, MatchEndReason reason, String damageCause)
{
    public MatchConclusion
    {
        Objects.requireNonNull(winnerId, "Winner cannot be null");
        Objects.requireNonNull(reason, "End reason cannot be null");
    }

    public static MatchConclusion defeat(UUID winnerId, String damageCause)
    {
        return new MatchConclusion(winnerId, MatchEndReason.DEFEAT, damageCause);
    }

    public static MatchConclusion disconnect(UUID winnerId)
    {
        return new MatchConclusion(winnerId, MatchEndReason.DISCONNECT, null);
    }

    public static MatchConclusion boundaryForfeit(UUID winnerId)
    {
        return new MatchConclusion(winnerId, MatchEndReason.BOUNDARY_FORFEIT, null);
    }
}
