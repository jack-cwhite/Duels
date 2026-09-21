package me.jackcw.duels.menu.admin.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaInstanceManager;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.entity.Player;

import java.util.Map;

public final class ArenaInstanceListMenu
{
    private final MenuManager menus;
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final MessageManager messageManager;
    private final ArenaInstanceDetailMenu arenaInstanceDetailMenu;

    public ArenaInstanceListMenu(Duels plugin, ArenaInstanceDetailMenu arenaInstanceDetailMenu)
    {
        this.menus = plugin.core().menus();
        this.arenaManager = plugin.getArenaManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.messageManager = plugin.core().messages();
        this.arenaInstanceDetailMenu = arenaInstanceDetailMenu;
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

        String menuKey = arena.getProvisioningMode() == me.jackcw.duels.arena.ArenaProvisioningMode.STATIC
                ? "arena-instance-list" : "arena-generated-list";
        menus.paginatedMenu(menuKey, arenaInstanceManager.getInstancesForArena(arenaId).stream()
                        .filter(arenaInstanceManager::isPlayable).toList())
                .placeholders(Map.of("arena-name", arena.getName()))
                .page(page)
                .item(instance -> Map.of(
                        "material", instance.isProvisioned() && instance.getDynamicState() == me.jackcw.duels.arena.DynamicArenaState.FAILED
                                ? "RED_DYE" : instance.isReady() ? "LIME_DYE" : "GRAY_DYE",
                        "id", instance.getId(),
                        "spawn1", instance.getSpawn1() != null ? "&aSet" : "&cNot Set",
                        "spawn2", instance.getSpawn2() != null ? "&aSet" : "&cNot Set",
                        "ready", instance.isReady() ? "&aYes" : "&cNo",
                        "origin", instance.getOrigin().name(),
                        "health", instance.isProvisioned() ? instance.getDynamicState().name() : "Manual",
                        "slot", instance.isProvisioned() ? instance.getDynamicSlotIndex() : "-"))
                .onClick((context, instance) -> context.openChild(() -> arenaInstanceDetailMenu.open(player, instance.getId())))
                .back()
                .open(player);
    }

    public void open(Player player, int arenaId)
    {
        open(player, arenaId, 0);
    }
}
