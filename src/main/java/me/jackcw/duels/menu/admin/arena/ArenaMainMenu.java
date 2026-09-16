package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
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

    public ArenaMainMenu(Duels plugin, ArenaListMenu arenaListMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.messageManager = plugin.getJCore().messages();
        this.arenaManager = plugin.getArenaManager();
        this.arenaListMenu = arenaListMenu;
    }

    public void open(Player player)
    {
        menus.menu("arena-main")
                .item("list", context ->
                        context.openChild(() -> arenaListMenu.open(player)))
                .item("create", context ->
                        context.requestInput(
                                prompt(Message.ARENA_CREATE_PROMPT),
                                name ->
                                {
                                    Arena arena = arenaManager.createArena(name);
                                    messageManager.send(player, Message.ARENA_CREATED, "name", name, "id", arena.getId());
                                    context.reopen();
                                },
                                () ->
                                {
                                    messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                                    context.reopen();
                                }))
                .back()
                .open(player);
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
