package me.jackcw.duels.reward;

/**
 * A permission that scales what a player earns.
 *
 * @param factor may be below 1 - a {@code 0.5} entry is a legitimate way to halve
 *               a group's earnings, and {@code 0} is how an admin excludes a group
 *               from rewards entirely without removing their ability to duel.
 */
public record RewardMultiplier(String permission, double factor)
{
}
