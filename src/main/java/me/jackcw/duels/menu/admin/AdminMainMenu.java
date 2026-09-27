package me.jackcw.duels.menu.admin;

import me.jackcw.duels.Duels;
import me.jackcw.duels.menu.admin.arena.ArenaMainMenu;
import me.jackcw.duels.menu.admin.kit.KitMainMenu;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.entity.Player;

public final class AdminMainMenu
{
    private final Duels plugin;
    private final MenuManager menus;
    private final MessageManager messageManager;
    private final ArenaMainMenu arenaMainMenu;
    private final KitMainMenu kitMainMenu;

    public AdminMainMenu(Duels plugin, ArenaMainMenu arenaMainMenu, KitMainMenu kitMainMenu)
    {
        this.plugin = plugin;
        this.menus = plugin.core().menus();
        this.messageManager = plugin.core().messages();
        this.arenaMainMenu = arenaMainMenu;
        this.kitMainMenu = kitMainMenu;
    }

    public void open(Player player)
    {
        ConfiguredMenu menu = menus.menu("admin-main");

        if (player.hasPermission("duels.admin.arena"))
            menu.item("arena", context -> context.openChild(() -> arenaMainMenu.open(player)));

        if (player.hasPermission("duels.admin.kit"))
            menu.item("kit", context -> context.openChild(() -> kitMainMenu.open(player)));

        if (player.hasPermission("duels.admin.reload"))
            menu.item("reload", context ->
            {
                plugin.core().config().reload();
                plugin.getSettings().reload(plugin.core().config(), plugin.getLogger());
                plugin.core().messages().reload();

                messageManager.send(player, Message.CONFIG_RELOADED);
                context.reopen();
            });

        menu.open(player);
    }
}
