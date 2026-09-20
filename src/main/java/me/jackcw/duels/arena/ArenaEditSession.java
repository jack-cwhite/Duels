package me.jackcw.duels.arena;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

public final class ArenaEditSession
{
    private final UUID playerUuid;
    private final int arenaId;
    private final ItemStack[] savedHotbar;
    private final ItemStack savedOffhand;
    private BukkitTask boundsParticleTask;

    public ArenaEditSession(Player player, Arena arena)
    {
        this.playerUuid = player.getUniqueId();
        this.arenaId = arena.getId();
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

    public int getArenaId()
    {
        return arenaId;
    }

    public ItemStack[] getSavedHotbar()
    {
        return savedHotbar;
    }

    public ItemStack getSavedOffhand()
    {
         return savedOffhand;
    }
}
