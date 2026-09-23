package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaProvisioningMode;
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
                            "status", describeStatus(arena, instances, ready, free),
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

    /**
     * A STATIC arena's playable-copy counts are the whole story, so they stay
     * front and centre. A DYNAMIC arena's counts are meaningless until it has
     * a build source and a captured template - until then, what an admin
     * actually needs to know is which of those two setup steps is missing.
     */
    private String describeStatus(Arena arena, int instances, int ready, int free)
    {
        if (arena.getProvisioningMode() != ArenaProvisioningMode.DYNAMIC)
            return "Playable copies: &f" + instances + " &7(&a" + ready + " ready&7, &b" + free + " free&7)";

        ArenaInstance source = arenaInstanceManager.getSource(arena.getId());

        if (source == null)
            return "&cNo build source registered yet";

        if (arena.getTemplateDefinition() == null)
            return "&eSource set up &7- &ecapture a template to enable matches";

        return "&f" + ready + "/" + instances + " &7generated copies ready (&b" + free + " free&7)";
    }
}
