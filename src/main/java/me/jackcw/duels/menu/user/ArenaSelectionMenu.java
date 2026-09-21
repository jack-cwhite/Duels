package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaSelection;
import me.jackcw.jcore.menu.MenuManager;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Lets a challenger choose Any or one enabled arena before sending a challenge. */
public final class ArenaSelectionMenu
{
    private final Duels plugin;
    private final MenuManager menus;

    public ArenaSelectionMenu(Duels plugin)
    {
        this.plugin = plugin;
        this.menus = plugin.core().menus();
    }

    public void open(Player player, String targetName, Consumer<ArenaSelection> onChoose)
    {
        List<ArenaSelection> choices = new ArrayList<>();
        choices.add(ArenaSelection.any());
        for (Arena arena : plugin.getArenaManager().getArenas())
            if (arena.isEnabled())
                choices.add(ArenaSelection.specific(arena.getId()));

        menus.paginatedMenu("arena-selection", choices)
                .placeholders(Map.of("player", targetName))
                .item(selection ->
                {
                    Arena arena = selection.isAny() ? null : plugin.getArenaManager().getArena(selection.arenaId());
                    return Map.of(
                            "material", selection.isAny() ? "COMPASS" : "MAP",
                            "name", selection.isAny() ? "Any Arena" : arena == null ? "Unavailable" : arena.getName(),
                            "description", selection.isAny() ? "First available arena" : "Only this arena",
                            "mode", arena == null ? "-" : arena.getProvisioningMode().name());
                })
                .onClick((context, selection) ->
                {
                    player.closeInventory();
                    onChoose.accept(selection);
                })
                .back()
                .open(player);
    }
}
