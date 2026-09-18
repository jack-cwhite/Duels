package me.jackcw.duels.arena;

import me.jackcw.duels.kit.Kit;
import org.bukkit.Location;

import java.util.Set;
import java.util.TreeSet;

public final class Arena
{
    private final int id;
    private String name;
    private Location spawn1;
    private Location spawn2;
    private Location boundsCorner1;
    private Location boundsCorner2;
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

    public void setEnabled(boolean enabled)
    {
        this.enabled = enabled;
    }

    public boolean isReady()
    {
        return spawn1 != null && spawn2 != null;
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
