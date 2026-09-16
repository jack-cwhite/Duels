package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.jcore.menu.MenuManager;
import org.bukkit.entity.Player;

import java.util.Map;

public final class ArenaListMenu
{
    private final MenuManager menus;
    private final ArenaManager arenaManager;
    private final ArenaDetailMenu arenaDetailMenu;

    public ArenaListMenu(Duels plugin, ArenaDetailMenu arenaDetailMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.arenaManager = plugin.getArenaManager();
        this.arenaDetailMenu = arenaDetailMenu;
    }

    public void open(Player player, int page)
    {
        menus.paginatedMenu("arena-list", arenaManager.getArenas())
                .page(page)
                .item(arena -> Map.of(
                        "material", arena.isReady() && arena.isEnabled() ? "LIME_DYE" : "GRAY_DYE",
                        "arena-name", arena.getName(),
                        "id", arena.getId(),
                        "spawn1", arena.getSpawn1() != null ? "&aSet" : "&cNot Set",
                        "spawn2", arena.getSpawn2() != null ? "&aSet" : "&cNot Set",
                        "ready", arena.isReady() ? "&aYes" : "&cNo",
                        "enabled", arena.isEnabled() ? "&aYes" : "&cNo"))
                .onClick((context, arena) -> context.openChild(() -> arenaDetailMenu.open(player, arena)))
                .back()
                .open(player);
    }

    public void open(Player player)
    {
        open(player, 0);
    }
}
