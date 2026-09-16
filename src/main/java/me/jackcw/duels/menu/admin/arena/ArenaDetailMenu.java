package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaMutationResult;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.Location;

import java.util.List;
import java.util.Map;

public final class ArenaDetailMenu
{
    private final MenuManager menus;
    private final ArenaManager arenaManager;
    private final MessageManager messageManager;
    private final ArenaKitMenu arenaKitMenu;

    public ArenaDetailMenu(Duels plugin, ArenaKitMenu arenaKitMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.arenaManager = plugin.getArenaManager();
        this.messageManager = plugin.getJCore().messages();
        this.arenaKitMenu = arenaKitMenu;
    }

    public void open(Player player, Arena arena)
    {
        open(player, arena.getId());
    }

    public void open(Player player, int arenaId)
    {
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            player.closeInventory();
            return;
        }

        menus.menu("arena-detail")
                .placeholders(Map.of("arena-name", arena.getName()))
                .item("toggle-available", !arena.isEnabled(), context ->
                {
                    ArenaMutationResult result = arenaManager.toggleEnabled(arenaId);

                    switch (result.status())
                    {
                        case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                        case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                        case SUCCESS -> messageManager.send(player, result.arena().isEnabled() ? Message.ARENA_ENABLED : Message.ARENA_DISABLED, "id", arenaId);
                    }

                    context.reopen();
                })
                .item("rename", context ->
                {
                    if (isInUse(player, arenaId, context))
                        return;

                    context.requestInput(
                            prompt(Message.ARENA_RENAME_PROMPT),
                            name ->
                            {
                                ArenaMutationResult result = arenaManager.rename(arenaId, name);

                                switch (result.status())
                                {
                                    case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                                    case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                                    case SUCCESS -> messageManager.send(player, Message.ARENA_RENAMED, "id", arenaId, "name", name);
                                }

                                context.reopen();
                            },
                            () ->
                            {
                                messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                                context.reopen();
                            });
                })
                .item("spawn1", context ->
                {
                    if (isInUse(player, arenaId, context))
                        return;

                    handleSpawnClick(player, arenaId, 1, context);
                })
                .item("spawn2", context ->
                {
                    if (isInUse(player, arenaId, context))
                        return;

                    handleSpawnClick(player, arenaId, 2, context);
                })
                .item("delete", context ->
                {
                    Arena current = requireArena(player, arenaId, context);

                    if (current == null)
                        return;

                    if (isInUse(player, arenaId, context))
                        return;

                    context.openChild(() -> menus.confirm()
                            .title("&8Delete Arena?")
                            .description(List.of(
                                    "&cDelete arena '" + current.getName() + "'?",
                                    "&cThis cannot be undone."
                            ))
                            .onConfirm(confirmContext ->
                            {
                                ArenaMutationResult result = arenaManager.deleteArena(arenaId);

                                switch (result.status())
                                {
                                    case NOT_FOUND ->
                                    {
                                        messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                                        player.closeInventory();
                                    }
                                    case IN_USE ->
                                    {
                                        messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                                        confirmContext.back();
                                    }
                                    case SUCCESS ->
                                    {
                                        messageManager.send(player, Message.ARENA_DELETED, "id", arenaId);

                                        if (!confirmContext.back(2))
                                            player.closeInventory();
                                    }
                                }
                            })
                            .open(player));
                })
                .item("kits", context -> context.openChild(() -> arenaKitMenu.open(player, arenaId)))
                .back()
                .open(player);
    }

    private Arena requireArena(Player player, int arenaId, MenuContext context)
    {
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            context.back();
        }

        return arena;
    }

    private boolean isInUse(Player player, int arenaId, MenuContext context)
    {
        if (!arenaManager.isActive(arenaId))
            return false;

        messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
        context.reopen();
        return true;
    }

    private void handleSpawnClick(Player player, int arenaId, int spawn, MenuContext context)
    {
        if (context.clickType().isRightClick())
        {
            Arena current = requireArena(player, arenaId, context);

            if (current == null)
                return;

            Location location = spawn == 1 ? current.getSpawn1() : current.getSpawn2();

            if (location == null)
                messageManager.send(player, Message.ARENA_SPAWN_NOT_SET, "spawn", spawn, "id", arenaId);
            else
                player.teleport(location);

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        ArenaMutationResult result = arenaManager.setSpawn(arenaId, spawn, player.getLocation());

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
            case SUCCESS -> messageManager.send(player, Message.ARENA_SPAWN_SET, "spawn", spawn, "id", arenaId);
        }

        context.reopen();
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
