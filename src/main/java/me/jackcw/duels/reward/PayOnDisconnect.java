package me.jackcw.duels.reward;

import java.util.Locale;

/**
 * Who gets paid when a duel ends because someone left.
 *
 * <p>Duels always counts a disconnect as a loss for the player who left, so the
 * question is not who lost - it is whether a result nobody fought to the end
 * should move money at all.
 */
public enum PayOnDisconnect
{
    /** Pay the winner, pay the quitter nothing. The default: quitting should not earn a consolation prize. */
    WINNER_ONLY,

    /** Treat it exactly like any other result. */
    BOTH,

    /** Pay nobody for a duel nobody finished. */
    NEITHER;

    /**
     * Parses a configured value, or returns {@code null} if it names nothing.
     *
     * <p>Hyphens are accepted as well as underscores because {@code winner-only} is
     * what the rest of rewards.yml looks like, and an admin who copies that style
     * should not have their file rejected over a punctuation mark.
     */
    public static PayOnDisconnect parse(String raw)
    {
        if (raw == null)
            return null;

        String normalised = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');

        for (PayOnDisconnect candidate : values())
            if (candidate.name().equals(normalised))
                return candidate;

        return null;
    }
}
