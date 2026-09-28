package me.jackcw.duels.reward;

import java.util.List;

/**
 * What {@link RewardTable#resolve} decided, and how it got there.
 *
 * <p>The trace exists because "why did that duel pay 200?" is unanswerable from the
 * bundle alone: five layers can contribute to one number. Carrying the working with
 * the answer is what lets {@code /duels rewards preview} explain itself instead of
 * asking an admin to redo the merge in their head.
 *
 * @param appliedLayers        the rewards.yml paths that contributed, least specific
 *                             first
 * @param multiplierPermission the permission whose multiplier won, or {@code null}
 *                             when no configured multiplier applied
 */
public record RewardResolution(RewardOutcome outcome, RewardBundle bundle, double multiplier,
                               String multiplierPermission, List<String> appliedLayers)
{
    public RewardResolution
    {
        appliedLayers = List.copyOf(appliedLayers);
    }

    public boolean hasMultiplier()
    {
        return multiplierPermission != null;
    }
}
