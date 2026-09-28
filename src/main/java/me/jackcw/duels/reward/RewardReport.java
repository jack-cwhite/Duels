package me.jackcw.duels.reward;

import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.kit.Kit;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the loaded reward configuration, and a single resolution, into lines an
 * administrator can read in chat.
 *
 * <p>Deliberately not routed through messages.yml. These lines are a table of facts
 * about what Duels loaded, not copy anyone would want to reword, and letting them be
 * edited would mean a diagnostic could be configured into lying about itself. The same
 * reasoning already applies to {@code /duels diagnostics}.
 *
 * <p>The preview exists because the merge has five layers, and "why did that duel pay
 * 200?" is otherwise a question an admin can only answer by redoing the merge in their
 * head. Showing the layers that contributed turns a guess into a check.
 */
public final class RewardReport
{
    private RewardReport()
    {
    }

    /** The overview: what loaded, whether it is usable, and how much of it is configured. */
    public static List<String> describe(RewardManager rewards)
    {
        RewardTable table = rewards.table();
        RewardTableLoader.Result load = rewards.lastLoad();
        List<String> lines = new ArrayList<>();

        lines.add("&e&lDuels rewards");

        if (!load.isValid())
        {
            lines.add("&c&lrewards.yml was refused - nothing will be paid until it is fixed.");

            for (String error : load.errors())
                lines.add("&c  - &f" + error);
        }
        else
        {
            lines.add(line("Enabled", table.isEnabled() ? "&ayes" : "&cno"));
        }

        lines.add(line("Pay on disconnect", table.payOnDisconnect().name()));
        lines.add(line("Arena overrides", String.valueOf(table.arenaOverrideCount())));
        lines.add(line("Kit overrides", String.valueOf(table.kitOverrideCount())));
        lines.add(line("Combination overrides", String.valueOf(table.combinationOverrideCount())));

        if (table.multipliers().isEmpty())
        {
            lines.add(line("Multipliers", "none"));
        }
        else
        {
            lines.add("&7Multipliers &8(highest applicable wins; they never stack)&7:");

            for (RewardMultiplier multiplier : table.multipliers())
                lines.add("&7  " + multiplier.permission() + " &8-> &f" + format(multiplier.factor()) + "x");
        }

        for (String warning : load.warnings())
            lines.add("&e  ! &f" + warning);

        lines.add("&8Use /duels rewards preview <win|loss> [kit] [arena] to see what a result pays.");

        return lines;
    }

    /**
     * What one result would pay, and which blocks of rewards.yml produced it.
     *
     * @param kit    the kit previewed, or {@code null} for a duel without one
     * @param arena  the arena previewed, or {@code null} to preview the defaults alone
     * @param viewer the name whose permissions the multiplier was tested against
     */
    public static List<String> preview(RewardResolution resolution, Kit kit, Arena arena, String viewer)
    {
        List<String> lines = new ArrayList<>();

        lines.add("&e&lReward preview");
        lines.add(line("Outcome", resolution.outcome().name()));
        lines.add(line("Kit", kit == null ? "&8none" : "&f" + kit.getName() + " &8(id " + kit.getId() + ")"));
        lines.add(line("Arena", arena == null ? "&8none" : "&f" + arena.getName() + " &8(id " + arena.getId() + ")"));
        lines.add(line("Permissions of", viewer));

        if (resolution.appliedLayers().isEmpty())
        {
            lines.add("&7No reward blocks apply to this combination.");
        }
        else
        {
            lines.add("&7Layers applied &8(least specific first)&7:");

            for (String layer : resolution.appliedLayers())
                lines.add("&8  - &7" + layer);
        }

        if (resolution.hasMultiplier())
            lines.add("&7Multiplier: &f" + format(resolution.multiplier()) + "x &8from "
                    + resolution.multiplierPermission());
        else
            lines.add("&7Multiplier: &fnone &8(1x)");

        if (resolution.bundle().isEmpty())
        {
            lines.add("&7Pays: &cnothing");
        }
        else
        {
            lines.add("&7Pays:");

            for (RewardGrant grant : resolution.bundle().grants())
                lines.add("&8  - &a" + describe(grant));
        }

        return lines;
    }

    /**
     * Money is labelled rather than formatted with a currency symbol, because the symbol
     * belongs to whatever economy plugin is installed and Duels has not asked it yet at
     * preview time.
     */
    private static String describe(RewardGrant grant)
    {
        return switch (grant)
        {
            case RewardGrant.MoneyGrant money -> money.describe() + " money";
            case RewardGrant.ExperienceGrant experience -> experience.describe();
            case RewardGrant.ItemGrant item -> item.describe();
            case RewardGrant.CommandGrant command -> "command &7" + command.describe();
        };
    }

    private static String format(double factor)
    {
        return factor == Math.floor(factor) ? String.valueOf((long) factor) : String.valueOf(factor);
    }

    private static String line(String label, String value)
    {
        return "&7" + label + ": &f" + value;
    }
}
