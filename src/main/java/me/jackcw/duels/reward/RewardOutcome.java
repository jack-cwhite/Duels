package me.jackcw.duels.reward;

import me.jackcw.duels.match.MatchResult;

import java.util.UUID;

/**
 * What a single player achieved in a finished match, as the reward tables see it.
 *
 * <p>Deliberately coarser than {@link me.jackcw.duels.match.MatchEndReason}: an
 * admin configuring payouts cares whether someone won, not whether the loss came
 * from a killing blow or a disconnect. Whether a given end reason pays at all is a
 * separate policy, held by {@link RewardTable}.
 *
 * <p>There is no {@code DRAW}. No gameplay rule in Duels can produce one, and
 * configuring and testing a state nobody can reach would be work spent on
 * fiction. If Phase 7's timed queue matches introduce draws, this enum is where
 * they land.
 */
public enum RewardOutcome
{
    WIN("win"),
    LOSS("loss");

    private final String configKey;

    RewardOutcome(String configKey)
    {
        this.configKey = configKey;
    }

    /** The key this outcome is written as in rewards.yml. */
    public String configKey()
    {
        return configKey;
    }

    public static RewardOutcome forPlayer(MatchResult result, UUID playerId)
    {
        return playerId.equals(result.winnerId()) ? WIN : LOSS;
    }
}
