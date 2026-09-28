package me.jackcw.duels.reward;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything one player is owed for one match result, after the reward tables have
 * been merged and any multiplier applied.
 *
 * <p>Immutable and computed once at result time rather than read from configuration
 * when it is granted. That matters: an admin editing rewards.yml while a duel is
 * being paid out must not change what that duel pays halfway through, and Slice 4's
 * recovery has to replay what was actually promised rather than whatever the file
 * says by then.
 */
public record RewardBundle(List<RewardGrant> grants)
{
    public static final RewardBundle EMPTY = new RewardBundle(List.of());

    public RewardBundle(List<RewardGrant> grants)
    {
        this.grants = List.copyOf(grants);
    }

    public boolean isEmpty()
    {
        return grants.isEmpty();
    }

    public <T extends RewardGrant> List<T> grantsOfType(Class<T> type)
    {
        List<T> matching = new ArrayList<>();

        for (RewardGrant grant : grants)
            if (type.isInstance(grant))
                matching.add(type.cast(grant));

        return matching;
    }
}
