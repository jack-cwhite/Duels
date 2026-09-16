package me.jackcw.duels.menu.admin.kit;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitIcons;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.PaginatedMenuBuilder;
import org.bukkit.entity.Player;

import java.util.Map;

public class KitListMenu
{
    private final MenuManager menus;
    private final KitManager kitManager;
    private final KitDetailMenu kitDetailMenu;

    public KitListMenu(Duels plugin, KitDetailMenu kitDetailMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.kitManager = plugin.getKitManager();
        this.kitDetailMenu = kitDetailMenu;
    }

    public void open(Player player, int page)
    {
        PaginatedMenuBuilder<Kit> builder = menus.paginatedMenu("kit-list", kitManager.getKits());

        builder
                .page(page)
                .itemFactory(kit -> KitIcons.build(kit, builder.entryTemplate(),
                        Map.of("kit-name", kit.getName(), "id", kit.getId())))
                .onClick((context, kit) -> context.openChild(() -> kitDetailMenu.open(player, kit)))
                .back()
                .open(player);
    }

    public void open(Player player)
    {
        open(player, 0);
    }
}
