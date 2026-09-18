package me.jackcw.duels.arena;

import org.bukkit.Location;

import java.util.UUID;

public final class ArenaInstance
{
    private final UUID instanceId;
    private final int arenaId;
    private final Location spawn1;
    private final Location spawn2;

    public ArenaInstance(int arenaId, Location spawn1, Location spawn2)
    {
        this.instanceId = UUID.randomUUID();
        this.arenaId = arenaId;
        this.spawn1 = spawn1.clone();
        this.spawn2 = spawn2.clone();
    }

    public UUID getInstanceId()
    {
        return instanceId;
    }

    public int getArenaId()
    {
        return arenaId;
    }

    public Location getSpawn1()
    {
        return spawn1.clone();
    }

    public Location getSpawn2()
    {
        return spawn2.clone();
    }
}
