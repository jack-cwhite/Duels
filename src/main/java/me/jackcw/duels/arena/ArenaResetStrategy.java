package me.jackcw.duels.arena;

/**
 * Restores an {@link ArenaInstance} to a clean state after a match finishes,
 * before it is released back to the {@link ArenaAllocator} for reuse.
 *
 * <p>{@code onComplete} exists because a reset is not guaranteed to finish
 * within the call to {@link #reset}: reverting many block changes in one tick
 * would stutter the server, so an implementation may spread the work across
 * several ticks and only call {@code onComplete} once the instance is
 * actually safe to hand to another match.
 */
public interface ArenaResetStrategy
{
    void reset(ArenaInstance instance, Runnable onComplete);

    /**
     * Clears anything that accumulated in an instance while it sat idle
     * between matches - a mob that spawned there, a bystander's death drops -
     * so a fresh match never inherits mess {@link #reset} had no reason to
     * have run against.
     */
    void prepareForMatch(ArenaInstance instance);
}
