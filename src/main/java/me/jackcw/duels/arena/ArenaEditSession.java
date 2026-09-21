package me.jackcw.duels.arena;

import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class ArenaEditSession
{
    private final UUID playerUuid;
    private final int instanceId;
    private final ItemStack[] savedHotbar;
    private final ItemStack savedOffhand;
    private BukkitTask boundsParticleTask;
    private Location structureCorner1;
    private Location structureCorner2;

    public ArenaEditSession(Player player, ArenaInstance instance)
    {
        this.playerUuid = player.getUniqueId();
        this.instanceId = instance.getId();
        this.savedHotbar = new ItemStack[9];

        for (int i = 0; i < 9; i++)
            savedHotbar[i] = player.getInventory().getItem(i);

        savedOffhand = player.getInventory().getItemInOffHand();
    }

    public BukkitTask getBoundsParticleTask()
    {
        return boundsParticleTask;
    }

    public void setBoundsParticleTask(BukkitTask boundsParticleTask)
    {
        this.boundsParticleTask = boundsParticleTask;
    }

    public UUID getPlayerUuid()
    {
        return playerUuid;
    }

    public int getInstanceId()
    {
        return instanceId;
    }

    public ItemStack[] getSavedHotbar()
    {
        return savedHotbar;
    }

    public ItemStack getSavedOffhand()
    {
         return savedOffhand;
    }

    public Location getStructureCorner1()
    {
        return structureCorner1;
    }

    public void setStructureCorner1(Location structureCorner1)
    {
        this.structureCorner1 = structureCorner1;
    }

    public Location getStructureCorner2()
    {
        return structureCorner2;
    }

    public void setStructureCorner2(Location structureCorner2)
    {
        this.structureCorner2 = structureCorner2;
    }
}
