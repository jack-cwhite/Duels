package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.stats.LeaderboardEntry;
import me.jackcw.duels.stats.LeaderboardMetric;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.duels.stats.StatsPlayer;
import me.jackcw.duels.stats.StatsQuery;
import me.jackcw.jcore.menu.MenuBuilder;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.ConfiguredMenu;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LeaderboardMenu
{
    private static final Logger LOGGER = Logger.getLogger(LeaderboardMenu.class.getName());
    private static final int LIMIT = 500;
    private static final int PAGE_SIZE = 45;

    private final Duels plugin;
    private final StatsManager stats;
    private final MenuManager menus;

    public LeaderboardMenu(Duels plugin)
    {
        this.plugin = plugin;
        this.stats = plugin.getStatsManager();
        this.menus = plugin.core().menus();
    }

    public void open(Player player)
    {
        LeaderboardSession session = new LeaderboardSession();
        menus.open(player, () -> render(player, session));
    }

    void openWithinNavigation(Player player)
    {
        render(player, new LeaderboardSession());
    }

    private void render(Player player, LeaderboardSession session)
    {
        LeaderboardMetric metric = session.metric;
        if (session.entries != null && session.loadedMetric == metric)
        {
            openRendered(player, session, session.entries);
            return;
        }
        stats.getLeaderboard(StatsQuery.leaderboard(), metric, LIMIT).whenComplete((entries, throwable) ->
        {
            if (!plugin.isEnabled())
                return;
            plugin.core().tasks().runSync(() ->
            {
                if (!player.isOnline() || metric != session.metric)
                    return;
                if (throwable != null)
                {
                    LOGGER.log(Level.WARNING, "Could not load leaderboard stats", throwable);
                    plugin.core().messages().send(player, Message.STATS_LOAD_FAILED);
                    return;
                }
                session.entries = entries;
                session.loadedMetric = metric;
                openRendered(player, session, entries);
            });
        });
    }

    private void openRendered(Player player, LeaderboardSession session, List<LeaderboardEntry> entries)
    {
        int totalPages = Math.max(1, (int) Math.ceil(entries.size() / (double) PAGE_SIZE));
        session.page = Math.min(session.page, totalPages - 1);
        int start = session.page * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, entries.size());
        ConfiguredMenu configured = menus.menu("stats-leaderboard")
                .placeholders(Map.of("metric", session.metric.displayName()));
        MenuBuilder menu = configured.builder();
        for (int index = start, slot = 0; index < end; index++, slot++)
        {
            LeaderboardEntry entry = entries.get(index);
            menu.item(slot, playerHead(entry, session.metric), context ->
                    plugin.getStatsProfileMenu().openAsChild(
                            context, new StatsPlayer(entry.playerId(), entry.playerName())));
        }

        List<String> metricLore = new ArrayList<>();
        metricLore.add("&7Current: &f" + session.metric.displayName());
        if (session.metric == LeaderboardMetric.WIN_RATE)
            metricLore.add("&7Minimum matches: &f" + stats.getWinRateMinimumMatches());
        metricLore.add("");
        metricLore.add("&eClick to change category");
        menu.item(47, StatsMenuItems.item(Material.COMPARATOR, "&eRanking Category", metricLore), context ->
        {
            session.metric = session.metric.next();
            session.page = 0;
            session.entries = null;
            context.reopen();
        });
        menu.item(51, StatsMenuItems.item(Material.NETHER_STAR, "&aYour Statistics",
                "&7Open your complete profile.", "", "&eClick to open"), context ->
                plugin.getStatsProfileMenu().openAsChild(context, new StatsPlayer(player.getUniqueId(), player.getName())));
        if (session.page > 0)
            menu.item(48, menus.navigationStyle().previous(), context ->
            {
                session.page--;
                context.reopen();
            });
        menu.item(49, menus.navigationStyle().pageIndicator(session.page + 1, totalPages));
        if (session.page + 1 < totalPages)
            menu.item(50, menus.navigationStyle().next(), context ->
            {
                session.page++;
                context.reopen();
            });
        configured.back().open(player);
    }

    private ItemStack playerHead(LeaderboardEntry entry, LeaderboardMetric metric)
    {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        Player online = Bukkit.getPlayer(entry.playerId());
        if (online != null)
            meta.setOwningPlayer(online);
        meta.displayName(StatsMenuItems.color("&f" + entry.playerName()));
        meta.lore(List.of(
                StatsMenuItems.color("&7" + metric.displayName() + ": &a" + formatValue(entry, metric)),
                StatsMenuItems.color("&7Matches: &f" + entry.stats().matches()),
                StatsMenuItems.color("&7Wins/Losses: &a" + entry.stats().wins() + "&7/&c" + entry.stats().losses()),
                Component.empty(),
                StatsMenuItems.color("&eClick to view profile")
        ));
        head.setItemMeta(meta);
        return head;
    }

    private String formatValue(LeaderboardEntry entry, LeaderboardMetric metric)
    {
        if (metric == LeaderboardMetric.WIN_RATE)
            return String.format(Locale.ROOT, "%.1f%%", entry.stats().winRate());
        return String.valueOf((int) entry.value(metric));
    }

    private static final class LeaderboardSession
    {
        private LeaderboardMetric metric = LeaderboardMetric.WINS;
        private LeaderboardMetric loadedMetric;
        private List<LeaderboardEntry> entries;
        private int page;
    }
}
