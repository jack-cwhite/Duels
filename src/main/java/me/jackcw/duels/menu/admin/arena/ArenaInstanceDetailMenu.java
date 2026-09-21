package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.ArenaEditManager;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaInstanceMutationResult;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class ArenaInstanceDetailMenu
{
    private final MenuManager menus;
    private final ArenaInstanceManager arenaInstanceManager;
    private final ArenaEditManager arenaEditManager;
    private final MessageManager messageManager;

    public ArenaInstanceDetailMenu(Duels plugin)
    {
        this.menus = plugin.core().menus();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.arenaEditManager = plugin.getArenaEditManager();
        this.messageManager = plugin.core().messages();
    }

    public void open(Player player, int instanceId)
    {
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);

        if (instance == null)
        {
            messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            player.closeInventory();
            return;
        }

        menus.menu("arena-instance-detail")
                .placeholders(Map.of(
                        "id", instanceId,
                        "bounds1", describe(instance.getBoundsCorner1()),
                        "bounds2", describe(instance.getBoundsCorner2())))
                .item("spawn1", context -> handleSpawnClick(player, instanceId, 1, context))
                .item("spawn2", context -> handleSpawnClick(player, instanceId, 2, context))
                .item("bounds1", context -> handleBoundsClick(player, instanceId, 1, context))
                .item("bounds2", context -> handleBoundsClick(player, instanceId, 2, context))
                .item("edit-mode", context ->
                {
                    ArenaInstance current = requireInstance(player, instanceId, context);

                    if (current == null)
                        return;

                    arenaEditManager.start(player, current);
                    player.closeInventory();
                })
                .item("delete", context ->
                {
                    ArenaInstance current = requireInstance(player, instanceId, context);

                    if (current == null)
                        return;

                    context.openChild(() -> menus.confirm()
                            .title("&8Delete Instance?")
                            .description(List.of(
                                    "&cDelete instance #" + current.getId() + "?",
                                    "&cThis cannot be undone."
                            ))
                            .onConfirm(confirmContext ->
                            {
                                ArenaInstanceMutationResult result = arenaInstanceManager.deleteInstance(instanceId);

                                switch (result.status())
                                {
                                    case NOT_FOUND ->
                                    {
                                        messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
                                        player.closeInventory();
                                    }
                                    case IN_USE ->
                                    {
                                        messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId);
                                        confirmContext.back();
                                    }
                                    case SUCCESS ->
                                    {
                                        messageManager.send(player, Message.ARENA_INSTANCE_DELETED, "id", instanceId);

                                        if (!confirmContext.back(2))
                                            player.closeInventory();
                                    }
                                }
                            })
                            .open(player));
                })
                .back()
                .open(player);
    }

    private ArenaInstance requireInstance(Player player, int instanceId, MenuContext context)
    {
        ArenaInstance instance = arenaInstanceManager.getInstance(instanceId);

        if (instance == null)
        {
            messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            context.back();
        }

        return instance;
    }

    private void handleSpawnClick(Player player, int instanceId, int spawn, MenuContext context)
    {
        if (context.clickType().isRightClick())
        {
            ArenaInstance current = requireInstance(player, instanceId, context);

            if (current == null)
                return;

            Location location = spawn == 1 ? current.getSpawn1() : current.getSpawn2();

            if (location == null)
                messageManager.send(player, Message.ARENA_SPAWN_NOT_SET, "spawn", spawn, "id", instanceId);
            else
                player.teleport(location);

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        ArenaInstanceMutationResult result = arenaInstanceManager.setSpawn(instanceId, spawn, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId);
            case SUCCESS -> messageManager.send(player, Message.ARENA_SPAWN_SET, "spawn", spawn, "id", instanceId);
        }

        context.reopen();
    }

    private void handleBoundsClick(Player player, int instanceId, int corner, MenuContext context)
    {
        if (context.clickType().isRightClick())
        {
            ArenaInstance current = requireInstance(player, instanceId, context);

            if (current == null)
                return;

            Location location = corner == 1 ? current.getBoundsCorner1() : current.getBoundsCorner2();

            if (location == null)
                messageManager.send(player, Message.ARENA_BOUNDS_NOT_SET, "corner", corner, "id", instanceId);
            else
                player.teleport(location);

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        ArenaInstanceMutationResult result = arenaInstanceManager.setBoundsCorner(instanceId, corner, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_INSTANCE_NOT_FOUND, "id", instanceId);
            case IN_USE -> messageManager.send(player, Message.ARENA_INSTANCE_IN_USE, "id", instanceId);
            case SUCCESS -> messageManager.send(player, Message.ARENA_BOUNDS_SET, "corner", corner, "id", instanceId);
        }

        context.reopen();
    }

    private String describe(Location location)
    {
        if (location == null)
            return "not set";

        return location.getBlockX() + ", " + location.getBlockY() + ", " + location.getBlockZ();
    }
}
