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
    private boolean boundsRequired = true;

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

    public boolean isSource()
    {
        return origin == ArenaInstanceOrigin.SOURCE;
    }

    public void markSource()
    {
        if (isProvisioned())
            throw new IllegalStateException("A generated arena copy cannot become a source");
        origin = ArenaInstanceOrigin.SOURCE;
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

    public boolean isBoundsRequired()
    {
        return boundsRequired;
    }

    /**
     * Marks this instance as predating the bounds requirement, so it keeps
     * working without bounds instead of being retroactively made not-ready.
     * Only the serializer should call this, when loading a record saved
     * before {@link #boundsRequired} existed.
     */
    public void exemptFromBoundsRequirement()
    {
        boundsRequired = false;
    }

    public boolean isReady()
    {
        return spawn1 != null && spawn2 != null && (!isProvisioned() || dynamicState == DynamicArenaState.READY)
                && (hasBounds() || !boundsRequired);
    }

    /**
     * Whether the given location falls inside this instance's bounds box.
     *
     * <p>Returns {@code false} if bounds are not set, or if the location is in
     * a different world to the bounds - a location cannot be "inside" a box
     * that has no defined extent, or that is defined in another world.
     *
     * <p>The comparison is made in whole-block coordinates, so the box is a
     * volume of blocks and both corner blocks are inside it. Comparing raw
     * coordinates instead looked equivalent but was not: callers pass a mix of
     * player positions (a point anywhere within a block) and
     * {@code Block.getLocation()} (that block's minimum corner), so a box set
     * from a standing position excluded its own minimum face - about half a
     * block in X and Z and a whole block in Y - while including the maximum
     * one. That asymmetry meant the lowest wall of an arena was treated as
     * outside it, so changes there were never rolled back and a player standing
     * against it could be warned for leaving bounds they were plainly inside.
     *
     * <p>Snapping the stored corners here as well as on the way in is what lets
     * arenas configured before corners were block-aligned pick up the fix on
     * load, with no migration.
     */
    public boolean contains(Location location)
    {
        if (!hasBounds() || !location.getWorld().equals(boundsCorner1.getWorld()))
            return false;

        int x = BlockCoordinates.blockCoordinate(location.getX());
        int y = BlockCoordinates.blockCoordinate(location.getY());
        int z = BlockCoordinates.blockCoordinate(location.getZ());

        return x >= minBoundsX() && x <= maxBoundsX()
                && y >= minBoundsY() && y <= maxBoundsY()
                && z >= minBoundsZ() && z <= maxBoundsZ();
    }

    /**
     * The size of the bounds box in blocks, or {@code null} if bounds are not
     * set.
     *
     * <p>Exists for admin feedback: the difference between a correct box and
     * one set a block out is invisible in a pair of coordinates but obvious in
     * a set of dimensions.
     */
    public ArenaStructureSize getBoundsSize()
    {
        if (!hasBounds())
            return null;

        return new ArenaStructureSize(
                maxBoundsX() - minBoundsX() + 1,
                maxBoundsY() - minBoundsY() + 1,
                maxBoundsZ() - minBoundsZ() + 1
        );
    }

    private int minBoundsX()
    {
        return Math.min(BlockCoordinates.blockCoordinate(boundsCorner1.getX()), BlockCoordinates.blockCoordinate(boundsCorner2.getX()));
    }

    private int maxBoundsX()
    {
        return Math.max(BlockCoordinates.blockCoordinate(boundsCorner1.getX()), BlockCoordinates.blockCoordinate(boundsCorner2.getX()));
    }

    private int minBoundsY()
    {
        return Math.min(BlockCoordinates.blockCoordinate(boundsCorner1.getY()), BlockCoordinates.blockCoordinate(boundsCorner2.getY()));
    }

    private int maxBoundsY()
    {
        return Math.max(BlockCoordinates.blockCoordinate(boundsCorner1.getY()), BlockCoordinates.blockCoordinate(boundsCorner2.getY()));
    }

    private int minBoundsZ()
    {
        return Math.min(BlockCoordinates.blockCoordinate(boundsCorner1.getZ()), BlockCoordinates.blockCoordinate(boundsCorner2.getZ()));
    }

    private int maxBoundsZ()
    {
        return Math.max(BlockCoordinates.blockCoordinate(boundsCorner1.getZ()), BlockCoordinates.blockCoordinate(boundsCorner2.getZ()));
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
