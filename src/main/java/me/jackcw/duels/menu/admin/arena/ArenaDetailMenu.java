package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaMutationResult;
import me.jackcw.duels.arena.BoundaryMode;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class ArenaDetailMenu
{
    private final MenuManager menus;
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final MessageManager messageManager;
    private final ArenaKitMenu arenaKitMenu;
    private final ArenaInstanceListMenu arenaInstanceListMenu;
    private final ArenaInstanceDetailMenu arenaInstanceDetailMenu;

    public ArenaDetailMenu(Duels plugin, ArenaKitMenu arenaKitMenu, ArenaInstanceListMenu arenaInstanceListMenu, ArenaInstanceDetailMenu arenaInstanceDetailMenu)
    {
        this.menus = plugin.core().menus();
        this.arenaManager = plugin.getArenaManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.messageManager = plugin.core().messages();
        this.arenaKitMenu = arenaKitMenu;
        this.arenaInstanceListMenu = arenaInstanceListMenu;
        this.arenaInstanceDetailMenu = arenaInstanceDetailMenu;
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
                .item("instances", context -> context.openChild(() -> arenaInstanceListMenu.open(player, arenaId)))
                .item("create-instance", context ->
                {
                    ArenaInstance instance = arenaInstanceManager.createInstance(arenaId);
                    messageManager.send(player, Message.ARENA_INSTANCE_CREATED, "id", instance.getId(), "arenaId", arenaId);
                    context.openChild(() -> arenaInstanceDetailMenu.open(player, instance.getId()));
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
                .item("boundary", Map.of(
                        "mode", arena.getBoundaryMode().name(),
                        "grace", arena.getGraceSeconds()
                ), context -> handleBoundaryClick(player, arenaId, context))
                .back()
                .open(player);
    }

    /**
     * Boundary mode and grace period are one concept to an admin but two
     * values, so they share a single item: left click steps through the modes,
     * right click asks for the grace period in chat. This mirrors the
     * left-to-change / right-for-the-other-action split the instance spawn and
     * bounds items already use.
     */
    private void handleBoundaryClick(Player player, int arenaId, MenuContext context)
    {
        Arena arena = requireArena(player, arenaId, context);

        if (arena == null)
            return;

        if (context.clickType().isRightClick())
        {
            context.requestInput(
                    prompt(Message.ARENA_BOUNDARY_GRACE_PROMPT),
                    input ->
                    {
                        int graceSeconds;

                        try
                        {
                            graceSeconds = Integer.parseInt(input.trim());
                        }
                        catch (NumberFormatException ignored)
                        {
                            messageManager.send(player, Message.MENU_INVALID_NUMBER);
                            context.reopen();
                            return;
                        }

                        applyBoundary(player, arenaId, null, graceSeconds, context);
                    },
                    () ->
                    {
                        messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                        context.reopen();
                    });

            return;
        }

        if (!context.clickType().isLeftClick())
            return;

        BoundaryMode[] modes = BoundaryMode.values();
        applyBoundary(player, arenaId, modes[(arena.getBoundaryMode().ordinal() + 1) % modes.length], null, context);
    }

    /**
     * A null {@code mode} or {@code graceSeconds} means "leave that one alone".
     * The current value is re-read here rather than captured at click time
     * because the chat prompt lets another admin change the arena in between.
     */
    private void applyBoundary(Player player, int arenaId, BoundaryMode mode, Integer graceSeconds, MenuContext context)
    {
        Arena arena = requireArena(player, arenaId, context);

        if (arena == null)
            return;

        BoundaryMode newMode = mode != null ? mode : arena.getBoundaryMode();
        int newGrace = graceSeconds != null ? graceSeconds : arena.getGraceSeconds();

        ArenaMutationResult result = arenaManager.setBoundaryMode(arenaId, newMode, newGrace);

        switch (result.status())
        {
            case NOT_FOUND -> messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            case IN_USE -> messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
            case SUCCESS -> messageManager.send(
                    player,
                    Message.ARENA_BOUNDARY_SET,
                    "id", arenaId,
                    "mode", newMode.name(),
                    "grace", newGrace
            );
        }

        context.reopen();
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
        if (!arenaManager.hasInstances(arenaId))
            return false;

        messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
        context.reopen();
        return true;
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
