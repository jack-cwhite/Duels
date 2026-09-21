package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaProvisioningMode;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

public final class ArenaMainMenu
{
    private final MenuManager menus;
    private final MessageManager messageManager;
    private final ArenaManager arenaManager;
    private final ArenaListMenu arenaListMenu;
    private final ArenaDetailMenu arenaDetailMenu;

    public ArenaMainMenu(Duels plugin, ArenaListMenu arenaListMenu, ArenaDetailMenu arenaDetailMenu)
    {
        this.menus = plugin.core().menus();
        this.messageManager = plugin.core().messages();
        this.arenaManager = plugin.getArenaManager();
        this.arenaListMenu = arenaListMenu;
        this.arenaDetailMenu = arenaDetailMenu;
    }

    public void open(Player player)
    {
        menus.menu("arena-main")
                .item("list", context ->
                        context.openChild(() -> arenaListMenu.open(player)))
                .item("create", context -> context.openChild(() -> menus.menu("arena-create-type")
                        .item("static", choice -> requestName(player, ArenaProvisioningMode.STATIC, choice))
                        .item("dynamic", choice -> requestName(player, ArenaProvisioningMode.DYNAMIC, choice))
                        .back()
                        .open(player)))
                .back()
                .open(player);
    }

    private void requestName(Player player, ArenaProvisioningMode mode, MenuContext context)
    {
        context.requestInput(prompt(Message.ARENA_CREATE_PROMPT), name ->
        {
            Arena arena = arenaManager.createArena(name, mode);
            messageManager.send(player, Message.ARENA_CREATED, "name", name, "id", arena.getId());
            context.openChild(() -> arenaDetailMenu.open(player, arena.getId()));
        }, () ->
        {
            messageManager.send(player, Message.MENU_INPUT_CANCELLED);
            context.reopen();
        });
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
