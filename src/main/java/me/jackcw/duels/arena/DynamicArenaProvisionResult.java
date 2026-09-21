package me.jackcw.duels.arena;

/** Result of making one new physical copy from a saved template. */
public record DynamicArenaProvisionResult(Status status, ArenaInstance instance)
{
    public enum Status { SUCCESS, TEMPLATE_UNAVAILABLE, CAPACITY_REACHED, FAILED }
    public static DynamicArenaProvisionResult success(ArenaInstance instance) { return new DynamicArenaProvisionResult(Status.SUCCESS, instance); }
    public static DynamicArenaProvisionResult failure(Status status) { return new DynamicArenaProvisionResult(status, null); }
}
