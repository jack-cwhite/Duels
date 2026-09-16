package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.arena.ArenaMutationResult;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitIcons;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.PaginatedMenuBuilder;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.entity.Player;

import java.util.Map;

import static me.jackcw.duels.arena.ArenaMutationResult.Status.SUCCESS;

public class ArenaKitMenu
{
    private final MenuManager menus;
    private final MessageManager messageManager;
    private final KitManager kitManager;
    private final ArenaManager arenaManager;

    public ArenaKitMenu(Duels plugin)
    {
        this.menus = plugin.getJCore().menus();
        this.messageManager = plugin.getJCore().messages();
        this.kitManager = plugin.getKitManager();
        this.arenaManager = plugin.getArenaManager();
    }

    public void open(Player player, int arenaId, int page)
    {
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null)
        {
            messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
            player.closeInventory();
            return;
        }

        PaginatedMenuBuilder<Kit> builder = menus.paginatedMenu("arena-kit", kitManager.getKits());

        builder.placeholders(Map.of("arena-name", arena.getName()))
                .page(page)
                .itemFactory(kit -> KitIcons.build(kit, builder.entryTemplate(), Map.of(
                        "kit-name", kit.getName(),
                        "id", kit.getId(),
                        "allowed", arena.isKitAllowed(kit) ? "&aAllowed" : "&cNot Allowed")))
                .onClick(
                        (context, kit) ->
                        {
                            ArenaMutationResult result = arenaManager.toggleKitAllowed(arenaId, kit.getId());

                            switch (result.status())
                            {
                                case NOT_FOUND ->
                                    messageManager.send(player, Message.ARENA_NOT_FOUND, "id", arenaId);
                                case IN_USE ->
                                    messageManager.send(player, Message.ARENA_IN_USE, "id", arenaId);
                                case SUCCESS -> context.reopen();
                            }

                            if (!result.status().equals(SUCCESS))
                                context.reopen();

                        }
                )
                .back()
                .open(player);
    }

    public void open(Player player, int arenaId)
    {
        open(player, arenaId, 0);
    }
}
