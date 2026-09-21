package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.jcore.menu.MenuManager;
import org.bukkit.entity.Player;

import java.util.Map;

public final class ArenaListMenu
{
    private final MenuManager menus;
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final ArenaDetailMenu arenaDetailMenu;

    public ArenaListMenu(Duels plugin, ArenaDetailMenu arenaDetailMenu)
    {
        this.menus = plugin.core().menus();
        this.arenaManager = plugin.getArenaManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.arenaDetailMenu = arenaDetailMenu;
    }

    public void open(Player player, int page)
    {
        menus.paginatedMenu("arena-list", arenaManager.getArenas())
                .page(page)
                .item(arena ->
                {
                    int instances = (int) arenaInstanceManager.getInstancesForArena(arena.getId()).stream()
                            .filter(arenaInstanceManager::isPlayable).count();
                    int ready = arenaInstanceManager.countReady(arena.getId());
                    int free = arenaInstanceManager.countFree(arena.getId());

                    return Map.of(
                            "material", ready > 0 && arena.isEnabled() ? "LIME_DYE" : "GRAY_DYE",
                            "arena-name", arena.getName(),
                            "mode", arena.getProvisioningMode().name(),
                            "id", arena.getId(),
                            "instances", instances,
                            "ready", ready + "/" + instances,
                            "free", free,
                            "enabled", arena.isEnabled() ? "&aYes" : "&cNo");
                })
                .onClick((context, arena) -> context.openChild(() -> arenaDetailMenu.open(player, arena)))
                .back()
                .open(player);
    }

    public void open(Player player)
    {
        open(player, 0);
    }
}
