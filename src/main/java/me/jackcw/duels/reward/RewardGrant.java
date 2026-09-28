package me.jackcw.duels.reward;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;

/**
 * One thing that is handed to one player because of one match result.
 *
 * <p>Sealed because the set is closed by design, not by accident. Granting has to
 * behave differently for each of these - money goes through an economy provider
 * and is verifiable, a command goes to the console and is not - so a {@code switch}
 * over the four cases is the honest shape. Sealing it means the compiler will
 * refuse to build a switch that forgets one, which is what we want the day a fifth
 * grant type is added.
 *
 * <p>The types are not interchangeable in what Duels can promise about them. See
 * the grant tier table in {@code docs/PHASE_6_DESIGN.md}: money, experience and
 * items can all be verified at the point of granting, while
 * {@code Bukkit.dispatchCommand} only reports that a command existed and did not
 * throw - never that the crate plugin on the other end actually gave anything.
 */
public sealed interface RewardGrant
{
    /** A short human-readable form, for previews, logs and player feedback. */
    String describe();

    record MoneyGrant(double amount) implements RewardGrant
    {
        @Override
        public String describe()
        {
            return amount == Math.floor(amount)
                    ? String.valueOf((long) amount)
                    : String.valueOf(amount);
        }
    }

    record ExperienceGrant(int amount) implements RewardGrant
    {
        @Override
        public String describe()
        {
            return amount + " XP";
        }
    }

    /**
     * A record gives shallow immutability only - the {@link ItemStack} reference
     * would still be shared and Bukkit item stacks are mutable, so a grant handed
     * to two players could be edited through either. Both the constructor and the
     * accessor copy to close that off.
     */
    record ItemGrant(ItemStack item) implements RewardGrant
    {
        public ItemGrant(ItemStack item)
        {
            this.item = Objects.requireNonNull(item, "item").clone();
        }

        @Override
        public ItemStack item()
        {
            return item.clone();
        }

        @Override
        public String describe()
        {
            return item.getAmount() + "x " + item.getType().name();
        }
    }

    /** Best effort only: never verified, never reversed, never replayed after a crash. */
    record CommandGrant(String command) implements RewardGrant
    {
        public CommandGrant(String command)
        {
            this.command = Objects.requireNonNull(command, "command");
        }

        @Override
        public String describe()
        {
            return "/" + command;
        }
    }
}
