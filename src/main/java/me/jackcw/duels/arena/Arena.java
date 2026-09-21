package me.jackcw.duels.arena;

import me.jackcw.duels.kit.Kit;

import java.util.Set;
import java.util.TreeSet;

/**
 * A duel template: policy that applies to every physical copy of this arena.
 *
 * <p>Spawns and bounds are not here - they are a property of a specific place
 * in the world, so they live on {@link ArenaInstance}, the registered copies
 * of this template. A template with zero instances is simply not usable yet.
 */
public final class Arena
{
    private final int id;
    private String name;
    private ArenaProvisioningMode provisioningMode = ArenaProvisioningMode.STATIC;
    private BoundaryMode boundaryMode = BoundaryMode.SOFT_RETURN;
    private int graceSeconds;
    private final Set<Integer> disallowedKitIds = new TreeSet<>();
    private boolean enabled;

    public Arena(int id, String name)
    {
        this.id = id;
        this.name = name;
        this.enabled = true;
    }

    public int getId()
    {
        return id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public ArenaProvisioningMode getProvisioningMode()
    {
        return provisioningMode;
    }

    public void setProvisioningMode(ArenaProvisioningMode provisioningMode)
    {
        this.provisioningMode = provisioningMode != null
                ? provisioningMode
                : ArenaProvisioningMode.STATIC;
    }

    public BoundaryMode getBoundaryMode()
    {
        return boundaryMode;
    }

    public void setBoundaryMode(BoundaryMode boundaryMode)
    {
        this.boundaryMode = boundaryMode != null ? boundaryMode : BoundaryMode.SOFT_RETURN;
    }

    public int getGraceSeconds()
    {
        return graceSeconds;
    }

    public void setGraceSeconds(int graceSeconds)
    {
        this.graceSeconds = Math.max(0, graceSeconds);
    }

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public boolean isKitAllowed(Kit kit)
    {
        return !disallowedKitIds.contains(kit.getId());
    }

    public boolean isKitAllowed(int kitId)
    {
        return !disallowedKitIds.contains(kitId);
    }

    public void allowKit(int id)
    {
        disallowedKitIds.remove(id);
    }

    public void disallowKit(int id)
    {
        disallowedKitIds.add(id);
    }

    public Set<Integer> getDisallowedKitIds()
    {
        return disallowedKitIds;
    }

    public boolean isEnabled()
    {
        return enabled;
    }
}
