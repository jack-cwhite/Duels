package me.jackcw.duels.arena;

import org.bukkit.Location;

/**
 * A single physical copy of an {@link Arena} template - one real place in the
 * world with its own spawns and bounds.
 *
 * <p>A template can be registered with several instances, each built by an
 * admin as its own space, so several matches of the same template can run at
 * once without sharing a location. Runtime allocation ({@link ArenaAllocator})
 * claims one instance per match; nothing about a template itself is claimed.
 */
public final class ArenaInstance
{
    private final int id;
    private final int arenaId;
    private Location spawn1;
    private Location spawn2;
    private Location boundsCorner1;
    private Location boundsCorner2;
    private ArenaInstanceOrigin origin = ArenaInstanceOrigin.MANUAL;
    private Integer dynamicSlotIndex;
    private Integer templateRevision;
    private ArenaStructureSize structureSize;
    private DynamicArenaState dynamicState;

    public ArenaInstance(int id, int arenaId)
    {
        this.id = id;
        this.arenaId = arenaId;
    }

    public int getId()
    {
        return id;
    }

    public int getArenaId()
    {
        return arenaId;
    }

    public static ArenaInstance provisioned(int id, int arenaId, int slotIndex, int templateRevision, ArenaStructureSize structureSize)
    {
        ArenaInstance instance = new ArenaInstance(id, arenaId);
        instance.configureProvisioned(slotIndex, templateRevision, structureSize, DynamicArenaState.PROVISIONING);
        return instance;
    }

    public ArenaInstanceOrigin getOrigin()
    {
        return origin;
    }

    public boolean isProvisioned()
    {
        return origin == ArenaInstanceOrigin.PROVISIONED;
    }

    public Integer getDynamicSlotIndex()
    {
        return dynamicSlotIndex;
    }

    public Integer getTemplateRevision()
    {
        return templateRevision;
    }

    public ArenaStructureSize getStructureSize()
    {
        return structureSize;
    }

    public DynamicArenaState getDynamicState()
    {
        return dynamicState;
    }

    public void configureProvisioned(int slotIndex, int templateRevision, ArenaStructureSize structureSize, DynamicArenaState state)
    {
        if (slotIndex < 0 || templateRevision < 1 || structureSize == null || state == null)
            throw new IllegalArgumentException("Provisioned arena metadata is invalid");

        this.origin = ArenaInstanceOrigin.PROVISIONED;
        this.dynamicSlotIndex = slotIndex;
        this.templateRevision = templateRevision;
        this.structureSize = structureSize;
        this.dynamicState = state;
    }

    public void setDynamicState(DynamicArenaState state)
    {
        if (!isProvisioned())
            throw new IllegalStateException("Manual arena instances do not have dynamic states");

        if (state == null || !canTransition(dynamicState, state))
            throw new IllegalArgumentException("Invalid dynamic arena state transition from " + dynamicState + " to " + state);

        dynamicState = state;
    }

    public Location getSpawn1()
    {
        return spawn1;
    }

    public void setSpawn1(Location spawn1)
    {
        this.spawn1 = spawn1;
    }

    public Location getSpawn2()
    {
        return spawn2;
    }

    public void setSpawn2(Location spawn2)
    {
        this.spawn2 = spawn2;
    }

    public Location getBoundsCorner1()
    {
        return boundsCorner1;
    }

    public void setBoundsCorner1(Location boundsCorner1)
    {
        this.boundsCorner1 = boundsCorner1;
    }

    public Location getBoundsCorner2()
    {
        return boundsCorner2;
    }

    public void setBoundsCorner2(Location boundsCorner2)
    {
        this.boundsCorner2 = boundsCorner2;
    }

    public boolean hasBounds()
    {
        return boundsCorner1 != null && boundsCorner2 != null;
    }

    public boolean isReady()
    {
        return spawn1 != null && spawn2 != null && (!isProvisioned() || dynamicState == DynamicArenaState.READY);
    }

    /**
     * Whether the given location falls inside this instance's bounds box.
     *
     * <p>Returns {@code false} if bounds are not set, or if the location is in
     * a different world to the bounds - a location cannot be "inside" a box
     * that has no defined extent, or that is defined in another world.
     */
    public boolean contains(Location location)
    {
        if (!hasBounds() || !location.getWorld().equals(boundsCorner1.getWorld()))
            return false;

        double minX = Math.min(boundsCorner1.getX(), boundsCorner2.getX());
        double maxX = Math.max(boundsCorner1.getX(), boundsCorner2.getX());
        double minY = Math.min(boundsCorner1.getY(), boundsCorner2.getY());
        double maxY = Math.max(boundsCorner1.getY(), boundsCorner2.getY());
        double minZ = Math.min(boundsCorner1.getZ(), boundsCorner2.getZ());
        double maxZ = Math.max(boundsCorner1.getZ(), boundsCorner2.getZ());

        return location.getX() >= minX && location.getX() <= maxX
                && location.getY() >= minY && location.getY() <= maxY
                && location.getZ() >= minZ && location.getZ() <= maxZ;
    }

    private static boolean canTransition(DynamicArenaState from, DynamicArenaState to)
    {
        if (from == to)
            return true;

        return switch (from)
        {
            case PROVISIONING -> to == DynamicArenaState.READY || to == DynamicArenaState.FAILED || to == DynamicArenaState.RETIRING;
            case READY -> to == DynamicArenaState.DIRTY || to == DynamicArenaState.RETIRING || to == DynamicArenaState.FAILED;
            case DIRTY -> to == DynamicArenaState.READY || to == DynamicArenaState.RETIRING || to == DynamicArenaState.FAILED;
            case RETIRING -> to == DynamicArenaState.FAILED;
            case FAILED -> to == DynamicArenaState.PROVISIONING || to == DynamicArenaState.RETIRING;
        };
    }
}
