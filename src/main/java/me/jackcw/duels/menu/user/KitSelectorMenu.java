package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitIcons;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.PaginatedMenuBuilder;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public class KitSelectorMenu
{
    private final MenuManager menus;
    private final MessageManager messageManager;
    private final MatchManager matchManager;
    private final KitViewMenu kitViewMenu;

    public KitSelectorMenu(Duels plugin, KitViewMenu kitViewMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.messageManager = plugin.getJCore().messages();
        this.matchManager = plugin.getMatchManager();
        this.kitViewMenu = kitViewMenu;
    }

    public void open(Player player, int page)
    {
        Match match = matchManager.getMatch(player.getUniqueId());

        if (match == null)
            return;

        if (match.getState() != MatchState.KIT_SELECTION)
            return;

        List<Kit> allowedKits = match.getAvailableKits();

        PaginatedMenuBuilder<Kit> builder = menus.paginatedMenu("kit-selector", allowedKits);

        builder
            .page(page)
            .itemFactory(kit -> KitIcons.build(kit, builder.entryTemplate(),
                  Map.of("kit-name", kit.getName(), "id", kit.getId())))
            .onClick(
                (context, kit) ->
                {
                    Match current = matchManager.getMatch(player.getUniqueId());

                    if (current == null || current.getState() != MatchState.KIT_SELECTION)
                    {
                      messageManager.send(player, Message.KIT_SELECTION_CLOSED);
                      player.closeInventory();
                      return;
                    }

                    if (context.clickType().isRightClick())
                    {
                      context.openChild(() -> kitViewMenu.open(player, kit));
                      return;
                    }

                    if (matchManager.selectKit(player.getUniqueId(), kit))
                      messageManager.send(player, Message.KIT_SELECTED, "name", kit.getName());
                    else
                      messageManager.send(player, Message.KIT_SELECTION_CLOSED);

                    player.closeInventory();
                  })
            .open(player);
    }

    public void open(Player player)
    {
        open(player, 0);
    }
}
