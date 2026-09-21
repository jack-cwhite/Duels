package me.jackcw.duels.spectator;

/**
 * Why a request to start spectating did or did not succeed.
 *
 * <p>Follows the same shape as {@code ArenaMutationResult}: the manager decides,
 * the caller only maps the answer to a message. Both the command and the menu go
 * through this, so they cannot drift apart on what the rules are.
 */
public enum SpectateResult
{
    SUCCESS,

    /** The requester is a combatant - they cannot watch and fight at once. */
    ALREADY_IN_MATCH,

    /** The requester is already watching this exact match. */
    ALREADY_SPECTATING,

    /** No match, or one that has already finished. */
    MATCH_UNAVAILABLE,

    /**
     * The arena has no bounds configured, so there is nothing to keep the
     * spectator inside it. Refused rather than allowed unconstrained, because a
     * spectator free to fly anywhere can drift into a neighbouring arena while
     * the plugin still believes they are watching this one.
     */
    NO_BOUNDS,

    /** The teleport into the arena was refused; the attempt was rolled back. */
    TELEPORT_FAILED
}
