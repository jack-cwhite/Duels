package me.jackcw.duels.reward;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * One block of rewards.yml, holding only the fields that block actually set.
 *
 * <p>A {@code null} field means "this layer said nothing about it", which is what
 * makes the merge in {@link RewardTable} sparse rather than wholesale. An empty
 * list is different from {@code null}: {@code items: []} is an admin explicitly
 * clearing the items a less specific layer set, and must be respected.
 *
 * <p>Getting this distinction wrong is the bug that would make configuring a kit's
 * experience silently stop an arena's money being paid - the kind of thing an admin
 * finds out about weeks later from a player complaint.
 */
public record RewardLayer(Double money, Integer experience, List<ItemStack> items, List<String> commands)
{
    public static final RewardLayer EMPTY = new RewardLayer(null, null, null, null);

    public RewardLayer(Double money, Integer experience, List<ItemStack> items, List<String> commands)
    {
        this.money = money;
        this.experience = experience;
        this.items = items == null ? null : List.copyOf(items);
        this.commands = commands == null ? null : List.copyOf(commands);
    }

    public boolean isEmpty()
    {
        return money == null && experience == null && items == null && commands == null;
    }

    /**
     * Returns this layer with every field {@code other} specified replaced by
     * {@code other}'s value, leaving the rest alone.
     */
    public RewardLayer overriddenBy(RewardLayer other)
    {
        if (other == null || other.isEmpty())
            return this;

        return new RewardLayer(
                other.money != null ? other.money : money,
                other.experience != null ? other.experience : experience,
                other.items != null ? other.items : items,
                other.commands != null ? other.commands : commands
        );
    }

    /**
     * Flattens this layer into the grants a player would actually receive.
     *
     * <p>The multiplier scales money and experience only. Items and commands are
     * left alone deliberately: "1.5 of a diamond sword" has no meaning, and
     * dispatching a crate-key command twice for a 2x would hand out a duplicate
     * reward that Duels cannot verify or take back.
     *
     * <p>A multiplier of exactly zero is the one case that suppresses everything,
     * including items and commands. An admin who writes {@code factor: 0} is
     * excluding that group from rewards, not asking for free gear with no money.
     *
     * <p>Zero amounts produce no grant at all, so a table that pays no money does
     * not tell the player they won nothing.
     */
    public RewardBundle toBundle(double multiplier)
    {
        if (multiplier == 0.0)
            return RewardBundle.EMPTY;

        List<RewardGrant> grants = new ArrayList<>();

        if (money != null && money > 0)
            grants.add(new RewardGrant.MoneyGrant(round(money * multiplier)));

        if (experience != null && experience > 0)
            grants.add(new RewardGrant.ExperienceGrant((int) Math.round(experience * multiplier)));

        if (items != null)
            for (ItemStack item : items)
                grants.add(new RewardGrant.ItemGrant(item));

        if (commands != null)
            for (String command : commands)
                grants.add(new RewardGrant.CommandGrant(command));

        return new RewardBundle(grants);
    }

    /**
     * Economy plugins deal in doubles, and {@code 100 * 1.5} is exact but
     * {@code 0.1 * 3} is not. Rounding to two decimals here keeps a balance from
     * ever reading as 149.99999999999997 coins.
     */
    private static double round(double amount)
    {
        return Math.round(amount * 100.0) / 100.0;
    }
}
