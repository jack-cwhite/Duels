package me.jackcw.duels.menu.admin;

import me.jackcw.duels.Duels;
import me.jackcw.duels.menu.admin.arena.ArenaMainMenu;
import me.jackcw.duels.menu.admin.kit.KitMainMenu;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.menu.MenuManager;
import org.bukkit.entity.Player;

public final class AdminMainMenu
{
    private final MenuManager menus;
    private final ArenaMainMenu arenaMainMenu;
    private final KitMainMenu kitMainMenu;

    public AdminMainMenu(Duels plugin, ArenaMainMenu arenaMainMenu, KitMainMenu kitMainMenu)
    {
        this.menus = plugin.getJCore().menus();
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

        menu.open(player);
    }
}
