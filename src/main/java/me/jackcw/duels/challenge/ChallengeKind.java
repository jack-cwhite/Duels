package me.jackcw.duels.challenge;

/**
 * Where a pending challenge came from.
 *
 * <p>The kind changes nothing about how a challenge is stored, claimed,
 * accepted or expired - that is deliberate, because duplicating the claim and
 * allocation workflow for rematches is the mistake worth avoiding. It exists so
 * the wording a player sees can be honest: being told "Challenge accepted" after
 * clicking Rematch reads like the plugin lost track of what you asked for.
 */
public enum ChallengeKind
{
    DIRECT,
    REMATCH
}
