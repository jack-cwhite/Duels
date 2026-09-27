package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.stats.MatchHistoryEntry;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.jcore.menu.MenuBuilder;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.ConfiguredMenu;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

final class StatsHistoryMenu
{
    private static final Logger LOGGER = Logger.getLogger(StatsHistoryMenu.class.getName());
    private static final int PAGE_SIZE = 45;

    private final Duels plugin;
    private final StatsManager stats;
    private final MenuManager menus;

    StatsHistoryMenu(Duels plugin)
    {
        this.plugin = plugin;
        this.stats = plugin.getStatsManager();
        this.menus = plugin.core().menus();
    }

    void open(Player viewer, StatsViewSession session)
    {
        long revision = session.revision();
        int page = session.historyPage();
        stats.getMatchHistory(session.query(), PAGE_SIZE + 1, page * PAGE_SIZE)
                .whenComplete((entries, throwable) -> runSync(() ->
                {
                    if (!viewer.isOnline() || revision != session.revision())
                        return;
                    if (throwable != null)
                    {
                        LOGGER.log(Level.WARNING, "Could not load match history", throwable);
                        plugin.core().messages().send(viewer, Message.STATS_LOAD_FAILED);
                        return;
                    }
                    render(viewer, session, entries);
                }));
    }

    private void render(Player viewer, StatsViewSession session, List<MatchHistoryEntry> loaded)
    {
        boolean hasNext = loaded.size() > PAGE_SIZE;
        List<MatchHistoryEntry> entries = hasNext ? loaded.subList(0, PAGE_SIZE) : loaded;
        ConfiguredMenu configured = menus.menu("stats-history")
                .placeholders(Map.of("player", session.target().name()));
        MenuBuilder builder = configured.builder();

        for (int slot = 0; slot < entries.size(); slot++)
        {
            MatchHistoryEntry entry = entries.get(slot);
            builder.item(slot, historyItem(entry), context -> context.openChild(() -> openDetail(viewer, entry)));
        }

        if (session.historyPage() > 0)
            builder.item(48, menus.navigationStyle().previous(), context ->
            {
                session.historyPage(session.historyPage() - 1);
                context.reopen();
            });
        builder.item(49, menus.navigationStyle().pageIndicator(session.historyPage() + 1,
                session.historyPage() + (hasNext ? 2 : 1)));
        if (hasNext)
            builder.item(50, menus.navigationStyle().next(), context ->
            {
                session.historyPage(session.historyPage() + 1);
                context.reopen();
            });
        configured.back().open(viewer);
    }

    private ItemStack historyItem(MatchHistoryEntry entry)
    {
        Material material = entry.won() ? Material.LIME_DYE : Material.RED_DYE;
        List<String> lore = new ArrayList<>();
        lore.add("&7Opponent: &f" + entry.opponentName());
        lore.add("&7Arena: &f" + arenaName(entry.arenaId()));
        lore.add("&7Your kit: &f" + kitName(entry.playerKitId()));
        lore.add("&7Opponent kit: &f" + kitName(entry.opponentKitId()));
        lore.add("&7Fight length: &f" + StatsMenuItems.duration(entry.combatDurationMillis()));
        lore.add("&7Ended: &f" + StatsMenuItems.date(entry.endedAt()));
        lore.add("");
        lore.add("&7" + endDescription(entry));
        lore.add("&eClick for full details");
        return StatsMenuItems.item(material, entry.won() ? "&aVictory" : "&cDefeat", lore);
    }

    private void openDetail(Player viewer, MatchHistoryEntry entry)
    {
        ConfiguredMenu configured = menus.menu("stats-match-detail")
                .placeholders(Map.of("id", entry.matchId()));
        MenuBuilder builder = configured.builder();
        List<String> lore = new ArrayList<>();
        lore.add("&7Result: " + (entry.won() ? "&aVictory" : "&cDefeat"));
        lore.add("&7Opponent: &f" + entry.opponentName());
        lore.add("&7Arena: &f" + arenaName(entry.arenaId()));
        lore.add("&7Your kit: &f" + kitName(entry.playerKitId()));
        lore.add("&7Opponent kit: &f" + kitName(entry.opponentKitId()));
        lore.add("&7Session length: &f" + StatsMenuItems.duration(entry.sessionDurationMillis()));
        lore.add("&7Combat length: &f" + StatsMenuItems.duration(entry.combatDurationMillis()));
        lore.add("&7Ended: &f" + StatsMenuItems.date(entry.endedAt()));
        lore.add("");
        lore.add("&7" + endDescription(entry));
        builder.item(13, StatsMenuItems.item(Material.WRITABLE_BOOK, "&eMatch Details", lore));
        configured.back().open(viewer);
    }

    private String endDescription(MatchHistoryEntry entry)
    {
        if (entry.endReason() == MatchEndReason.DISCONNECT)
            return entry.won() ? entry.opponentName() + " disconnected " + phase(entry.endedState())
                    : "Disconnected " + phase(entry.endedState());
        if (entry.endReason() == MatchEndReason.BOUNDARY_FORFEIT)
            return entry.won() ? entry.opponentName() + " forfeited by leaving the arena"
                    : "Forfeited by leaving the arena";
        if (entry.damageCause() != null)
            return (entry.won() ? "Won" : "Lost") + " by " + StatsMenuItems.friendlyEnum(entry.damageCause());
        return entry.won() ? "Defeated " + entry.opponentName() : "Defeated by " + entry.opponentName();
    }

    private String phase(MatchState state)
    {
        return switch (state)
        {
            case PREGAME -> "during kit selection";
            case GRACE -> "during the countdown";
            case IN_PROGRESS -> "during combat";
            case ENDED -> "after the match";
        };
    }

    private String arenaName(int id)
    {
        Arena arena = plugin.getArenaManager().getArena(id);
        return arena != null ? arena.getName() + " (#" + id + ")" : "Deleted Arena #" + id;
    }

    private String kitName(Integer id)
    {
        if (id == null)
            return "No kit applied";
        Kit kit = plugin.getKitManager().getKit(id);
        return kit != null ? kit.getName() + " (#" + id + ")" : "Deleted Kit #" + id;
    }

    private void runSync(Runnable action)
    {
        if (plugin.isEnabled())
            plugin.core().tasks().runSync(action);
    }
}
