package me.jackcw.duels.reward;

import me.jackcw.duels.match.MatchEndReason;
import org.bukkit.permissions.Permissible;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The loaded contents of rewards.yml, and the only thing that decides what a result pays.
 *
 * <p>Immutable on purpose. A reload builds a whole new table rather than mutating this
 * one, so a resolution happening while an admin runs {@code /duels reload} can never
 * see half the old file and half the new one.
 *
 * <p>Resolution is a <em>sparse field merge</em> over five layers, least specific first:
 * {@code defaults.<outcome>}, then the arena override, then the kit override, then the
 * combination override naming both, and finally the single highest multiplier the player
 * holds. Each layer replaces only the fields it actually sets, so an arena that raises
 * money does not wipe the default items, and a kit that sets experience does not wipe the
 * arena's money. Kit beats arena on the same field because a kit is the more deliberate
 * choice a player makes.
 */
public final class RewardTable
{
    /** Identifies an override that applies only to one kit played in one arena. */
    public record Combination(int arenaId, int kitId)
    {
    }

    /**
     * What resolution produces when rewards are switched off, or when rewards.yml
     * failed to load. Paying nothing is the only safe failure mode: a malformed file
     * must not be able to invent money.
     */
    private static final RewardTable DISABLED = new RewardTable(false, PayOnDisconnect.WINNER_ONLY,
            new EnumMap<>(RewardOutcome.class), Map.of(), Map.of(), Map.of(), List.of());

    private final boolean enabled;
    private final PayOnDisconnect payOnDisconnect;
    private final Map<RewardOutcome, RewardLayer> defaults;
    private final Map<Integer, Map<RewardOutcome, RewardLayer>> arenaOverrides;
    private final Map<Integer, Map<RewardOutcome, RewardLayer>> kitOverrides;
    private final Map<Combination, Map<RewardOutcome, RewardLayer>> combinationOverrides;
    private final List<RewardMultiplier> multipliers;

    RewardTable(boolean enabled, PayOnDisconnect payOnDisconnect,
                Map<RewardOutcome, RewardLayer> defaults,
                Map<Integer, Map<RewardOutcome, RewardLayer>> arenaOverrides,
                Map<Integer, Map<RewardOutcome, RewardLayer>> kitOverrides,
                Map<Combination, Map<RewardOutcome, RewardLayer>> combinationOverrides,
                List<RewardMultiplier> multipliers)
    {
        this.enabled = enabled;
        this.payOnDisconnect = payOnDisconnect;
        this.defaults = Map.copyOf(defaults);
        this.arenaOverrides = Map.copyOf(arenaOverrides);
        this.kitOverrides = Map.copyOf(kitOverrides);
        this.combinationOverrides = Map.copyOf(combinationOverrides);
        this.multipliers = List.copyOf(multipliers);
    }

    public static RewardTable disabled()
    {
        return DISABLED;
    }

    /**
     * Merges every applicable layer, applies the best multiplier the player holds, and
     * reports both the result and the path it took to get there.
     *
     * @param kitId   the kit the player used, or {@code null} if they duelled without one
     * @param arenaId the arena the duel was fought in, or {@code null} when previewing
     *                without naming one
     * @param player  who to test multiplier permissions against; {@code null} skips
     *                multipliers entirely, since a permission only exists for a real player
     */
    public RewardResolution resolve(RewardOutcome outcome, Integer kitId, Integer arenaId, Permissible player)
    {
        List<String> appliedLayers = new ArrayList<>();
        RewardLayer merged = RewardLayer.EMPTY;

        merged = apply(merged, defaults.get(outcome), "defaults." + outcome.configKey(), appliedLayers);

        if (arenaId != null)
            merged = apply(merged, layerFor(arenaOverrides.get(arenaId), outcome),
                    "overrides.arenas." + arenaId + "." + outcome.configKey(), appliedLayers);

        if (kitId != null)
            merged = apply(merged, layerFor(kitOverrides.get(kitId), outcome),
                    "overrides.kits." + kitId + "." + outcome.configKey(), appliedLayers);

        if (arenaId != null && kitId != null)
            merged = apply(merged, layerFor(combinationOverrides.get(new Combination(arenaId, kitId)), outcome),
                    "overrides.combinations[arena " + arenaId + ", kit " + kitId + "]." + outcome.configKey(),
                    appliedLayers);

        RewardMultiplier best = bestMultiplier(player);
        double factor = best == null ? 1.0 : best.factor();

        return new RewardResolution(outcome, enabled ? merged.toBundle(factor) : RewardBundle.EMPTY,
                factor, best == null ? null : best.permission(), appliedLayers);
    }

    /**
     * Whether a result that ended this way pays at all.
     *
     * <p>Only a disconnect is treated specially, because it is the one ending where the
     * duel did not really happen and paying it invites two accounts trading wins by
     * quitting. A boundary forfeit still pays: the player was there and chose to walk
     * out, which is a genuine loss, and Slice 5's minimum-duration rule is the right
     * tool against anyone looping it.
     */
    public boolean pays(MatchEndReason reason, RewardOutcome outcome)
    {
        if (reason != MatchEndReason.DISCONNECT)
            return true;

        return switch (payOnDisconnect)
        {
            case BOTH -> true;
            case WINNER_ONLY -> outcome == RewardOutcome.WIN;
            case NEITHER -> false;
        };
    }

    /**
     * The highest-factor multiplier the player actually holds, or {@code null} if none
     * applies.
     *
     * <p>Note the comparison starts from the first applicable entry rather than from
     * 1.0. Starting at 1.0 would silently discard every penalty an admin configured, so
     * a lone {@code factor: 0.5} would appear to do nothing at all.
     */
    private RewardMultiplier bestMultiplier(Permissible player)
    {
        if (player == null)
            return null;

        RewardMultiplier best = null;

        for (RewardMultiplier multiplier : multipliers)
        {
            if (!player.hasPermission(multiplier.permission()))
                continue;

            if (best == null || multiplier.factor() > best.factor())
                best = multiplier;
        }

        return best;
    }

    private static RewardLayer apply(RewardLayer merged, RewardLayer layer, String path, List<String> appliedLayers)
    {
        if (layer == null)
            return merged;

        appliedLayers.add(path);
        return merged.overriddenBy(layer);
    }

    private static RewardLayer layerFor(Map<RewardOutcome, RewardLayer> outcomes, RewardOutcome outcome)
    {
        return outcomes == null ? null : outcomes.get(outcome);
    }

    public boolean isEnabled()
    {
        return enabled;
    }

    public PayOnDisconnect payOnDisconnect()
    {
        return payOnDisconnect;
    }

    public List<RewardMultiplier> multipliers()
    {
        return multipliers;
    }

    public int arenaOverrideCount()
    {
        return arenaOverrides.size();
    }

    public int kitOverrideCount()
    {
        return kitOverrides.size();
    }

    public int combinationOverrideCount()
    {
        return combinationOverrides.size();
    }
}
