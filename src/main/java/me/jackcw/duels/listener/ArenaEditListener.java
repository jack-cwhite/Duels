package me.jackcw.duels.listener;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.ArenaEditManager;
import me.jackcw.duels.arena.ArenaEditSession;
import me.jackcw.duels.arena.ArenaEditTool;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaInstanceMutationResult;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;

public final class ArenaEditListener implements Listener
{
    private final Duels plugin;
    private final ArenaEditManager arenaEditManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final MessageManager messageManager;

    public ArenaEditListener(Duels plugin)
    {
        this.plugin = plugin;
        this.arenaEditManager = plugin.getArenaEditManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.messageManager = plugin.core().messages();
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event)
    {
        if (event.getPlayer() instanceof Player player && arenaEditManager.isEditing(player))
        {
            messageManager.send(player, Message.CANNOT_OPEN_INVENTORY_WHILE_IN_EDIT_MODE);
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerDropItem(PlayerDropItemEvent event)
    {
        if (!arenaEditManager.isEditing(event.getPlayer().getUniqueId()))
            return;

        ArenaEditTool tool = arenaEditManager.getTool(event.getItemDrop().getItemStack());

        if (tool == null)
            return;

        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event)
    {
        if (event.getWhoClicked() instanceof Player player && arenaEditManager.isEditing(player))
            event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event)
    {
        if (event.getWhoClicked() instanceof Player player && arenaEditManager.isEditing(player))
            event.setCancelled(true);
    }

    @EventHandler
    public void onSwapHandItems(PlayerSwapHandItemsEvent event)
    {
        if (arenaEditManager.isEditing(event.getPlayer()))
            event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event)
    {
        Player player = event.getPlayer();

        if (!arenaEditManager.isEditing(player))
            return;

        event.getDrops().removeIf(arenaEditManager::isTool);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event)
    {
        Player player = event.getPlayer();

        if (!arenaEditManager.isEditing(player))
            return;

        plugin.core().tasks().runSyncLater(() ->
        {
            if (player.isOnline() && arenaEditManager.isEditing(player))
                arenaEditManager.giveTools(player);
        }, 1L);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event)
    {
        if (event.getHand() != EquipmentSlot.HAND)
            return;

        Player player = event.getPlayer();

        if (!arenaEditManager.isEditing(player))
            return;

        ArenaEditTool tool = arenaEditManager.getTool(event.getItem());

        if (tool == null)
            return;

        event.setCancelled(true);

        Action action = event.getAction();
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;

        if (!right && !left)
            return;

        if (tool == ArenaEditTool.EXIT)
        {
            arenaEditManager.end(player);
            messageManager.send(player, Message.ARENA_EDIT_MODE_EXITED);
            return;
        }

        ArenaEditSession session = arenaEditManager.getSession(player);
        ArenaInstance instance = arenaInstanceManager.getInstance(session.getInstanceId());

        if (instance == null)
        {
            arenaEditManager.end(player);
            messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", session.getInstanceId());
            return;
        }

        if (!instance.isSource() && (tool == ArenaEditTool.STRUCTURE_CORNER_1 || tool == ArenaEditTool.STRUCTURE_CORNER_2))
            return;

        if (right)
            handleTeleport(player, session, instance, tool);
        else
            handleSet(player, session, instance, tool);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event)
    {
        Player player = event.getPlayer();

        if (arenaEditManager.isEditing(player))
            arenaEditManager.end(player);
        arenaEditManager.clearCaptureDraft(player.getUniqueId());
    }

    private void handleTeleport(Player player, ArenaEditSession session, ArenaInstance instance, ArenaEditTool tool)
    {
        Location location = switch (tool)
        {
            case SPAWN_1 -> instance.getSpawn1();
            case SPAWN_2 -> instance.getSpawn2();
            case STRUCTURE_CORNER_1 -> session.getStructureCorner1();
            case STRUCTURE_CORNER_2 -> session.getStructureCorner2();
            case BOUNDS_CORNER_1 -> instance.getBoundsCorner1();
            case BOUNDS_CORNER_2 -> instance.getBoundsCorner2();
            case EXIT -> null;
        };

        if (location != null)
        {
            player.teleport(location);
            return;
        }

        if (tool == ArenaEditTool.SPAWN_1 || tool == ArenaEditTool.SPAWN_2)
            messageManager.send(player, Message.ARENA_SPAWN_NOT_SET, "spawn", toolNumber(tool), "id", instance.getId());
        else if (tool == ArenaEditTool.STRUCTURE_CORNER_1 || tool == ArenaEditTool.STRUCTURE_CORNER_2)
            messageManager.send(player, Message.ARENA_STRUCTURE_CORNER_NOT_SET, "corner", toolNumber(tool));
        else
            messageManager.send(player, Message.ARENA_BOUNDS_NOT_SET, "corner", toolNumber(tool), "id", instance.getId());
    }

    private void handleSet(Player player, ArenaEditSession session, ArenaInstance instance, ArenaEditTool tool)
    {
        if (tool == ArenaEditTool.STRUCTURE_CORNER_1 || tool == ArenaEditTool.STRUCTURE_CORNER_2)
        {
            if (tool == ArenaEditTool.STRUCTURE_CORNER_1)
                arenaEditManager.setStructureCorner(player, instance.getId(), 1, player.getLocation());
            else
                arenaEditManager.setStructureCorner(player, instance.getId(), 2, player.getLocation());

            messageManager.send(player, Message.ARENA_STRUCTURE_CORNER_SET, "corner", toolNumber(tool));
            return;
        }

        ArenaInstanceMutationResult result = switch (tool)
        {
            case SPAWN_1 -> arenaInstanceManager.setSpawn(instance.getId(), 1, player.getLocation());
            case SPAWN_2 -> arenaInstanceManager.setSpawn(instance.getId(), 2, player.getLocation());
            case STRUCTURE_CORNER_1, STRUCTURE_CORNER_2 -> null;
            case BOUNDS_CORNER_1 -> arenaInstanceManager.setBoundsCorner(instance.getId(), 1, player.getLocation());
            case BOUNDS_CORNER_2 -> arenaInstanceManager.setBoundsCorner(instance.getId(), 2, player.getLocation());
            case EXIT -> null;
        };

        if (result == null)
            return;

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instance.getId());
            case IN_USE -> messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instance.getId());
            case SUCCESS ->
            {
                if (tool == ArenaEditTool.SPAWN_1 || tool == ArenaEditTool.SPAWN_2)
                    messageManager.send(player, Message.ARENA_SPAWN_SET, "spawn", toolNumber(tool), "id", instance.getId());
                else
                    messageManager.send(player, Message.ARENA_BOUNDS_SET, "corner", toolNumber(tool), "id", instance.getId());
            }
        }
    }

    private int toolNumber(ArenaEditTool tool)
    {
        return switch (tool)
        {
            case SPAWN_1, BOUNDS_CORNER_1 -> 1;
            case SPAWN_2, BOUNDS_CORNER_2 -> 2;
            case STRUCTURE_CORNER_1 -> 1;
            case STRUCTURE_CORNER_2 -> 2;
            case EXIT -> 0;
        };
    }
}
