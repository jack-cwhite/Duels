package me.jackcw.duels.arena;

/** The result of asynchronous arena acquisition, before any player is changed. */
public record ArenaAllocationResult(Status status, ArenaInstance instance)
{
    public enum Status
    {
        SUCCESS,
        NO_ARENA_AVAILABLE,
        ARENA_NOT_FOUND,
        ARENA_DISABLED,
        TEMPLATE_UNAVAILABLE,
        CAPACITY_REACHED,
        PROVISIONING_FAILED
    }

    public static ArenaAllocationResult success(ArenaInstance instance) { return new ArenaAllocationResult(Status.SUCCESS, instance); }
    public static ArenaAllocationResult failure(Status status) { return new ArenaAllocationResult(status, null); }
}
