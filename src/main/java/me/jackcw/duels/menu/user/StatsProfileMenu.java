package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.stats.PlayerStats;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.duels.stats.StatsPlayer;
import me.jackcw.duels.stats.StatsQuery;
import me.jackcw.jcore.menu.MenuBuilder;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class StatsProfileMenu
{
    private static final Logger LOGGER = Logger.getLogger(StatsProfileMenu.class.getName());

    private final Duels plugin;
    private final StatsManager stats;
    private final MenuManager menus;
    private final MessageManager messages;
    private final StatsHistoryMenu historyMenu;

    public StatsProfileMenu(Duels plugin)
    {
        this.plugin = plugin;
        this.stats = plugin.getStatsManager();
        this.menus = plugin.core().menus();
        this.messages = plugin.core().messages();
        this.historyMenu = new StatsHistoryMenu(plugin);
    }

    public void open(Player viewer)
    {
        open(viewer, new StatsPlayer(viewer.getUniqueId(), viewer.getName()));
    }

    public void open(Player viewer, StatsPlayer target)
    {
        StatsViewSession session = new StatsViewSession(target);
        menus.open(viewer, () -> render(viewer, session));
    }

    public void openAsChild(MenuContext context, StatsPlayer target)
    {
        StatsViewSession session = new StatsViewSession(target);
        context.openChild(() -> render(context.player(), session));
    }

    public void openByName(Player viewer, String name)
    {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null)
        {
            open(viewer, new StatsPlayer(online.getUniqueId(), online.getName()));
            return;
        }

        stats.findPlayer(name).whenComplete((target, throwable) -> runSync(() ->
        {
            if (!viewer.isOnline())
                return;
            if (throwable != null)
            {
                loadFailed(viewer, throwable);
                return;
            }
            if (target.isEmpty())
            {
                messages.send(viewer, Message.STATS_PLAYER_NOT_FOUND, "player", name);
                return;
            }
            open(viewer, target.get());
        }));
    }

    private void render(Player viewer, StatsViewSession session)
    {
        long revision = session.revision();
        StatsQuery query = session.query();
        stats.getPlayerStats(query).whenComplete((result, throwable) -> runSync(() ->
        {
            if (!viewer.isOnline() || revision != session.revision())
                return;
            if (throwable != null)
            {
                loadFailed(viewer, throwable);
                return;
            }
            openRendered(viewer, session, result);
        }));
    }

    private void openRendered(Player viewer, StatsViewSession session, PlayerStats result)
    {
        StatsQuery filters = session.filters();
        ConfiguredMenu configured = menus.menu("stats-profile")
                .placeholders(Map.of("player", session.target().name()));
        MenuBuilder builder = configured.builder();

        builder.item(4, profileHead(session.target(), result, session.query()));
        builder.item(10, StatsMenuItems.item(Material.MAP, "&eArena",
                "&7Current: &f" + arenaName(filters.arenaId()), "", "&eLeft-click to choose", "&eRight-click to enter an ID"),
                context ->
                {
                    if (context.clickType().isRightClick())
                        requestId(context, "arena", id -> session.filters(session.filters().withArena(id)));
                    else
                        context.openChild(() -> openArenaFilter(viewer, session));
                });
        builder.item(12, StatsMenuItems.item(Material.IRON_SWORD, "&e" + session.target().name() + "'s Kit",
                "&7Current: &f" + kitName(filters.playerKitId()), "", "&eLeft-click to choose", "&eRight-click to enter an ID"),
                context ->
                {
                    if (context.clickType().isRightClick())
                        requestId(context, "kit", id -> session.filters(session.filters().withPlayerKit(id)));
                    else
                        context.openChild(() -> openKitFilter(viewer, session, false));
                });
        builder.item(14, StatsMenuItems.item(Material.PLAYER_HEAD, "&eOpponent",
                "&7Current: &f" + opponentName(filters.opponentId()), "", "&eLeft-click and type a player",
                filters.opponentId() == null && !viewer.getUniqueId().equals(session.target().id())
                        ? "&eRight-click to compare with you" : "&7Right-click to clear"),
                context -> handleOpponent(context, session, viewer));
        builder.item(16, StatsMenuItems.item(Material.SHIELD, "&eOpponent's Kit",
                "&7Current: &f" + kitName(filters.opponentKitId()), "", "&eLeft-click to choose", "&eRight-click to enter an ID"),
                context ->
                {
                    if (context.clickType().isRightClick())
                        requestId(context, "opponent kit", id -> session.filters(session.filters().withOpponentKit(id)));
                    else
                        context.openChild(() -> openKitFilter(viewer, session, true));
                });
        builder.item(22, StatsMenuItems.item(Material.CLOCK, "&eTime Period",
                "&7Current: &f" + session.timePeriod().displayName(), "", "&eClick to cycle"), context ->
        {
            session.cycleTimePeriod();
            context.reopen();
        });
        builder.item(29, StatsMenuItems.item(Material.BOOK, "&aMatch History",
                "&7View every match covered", "&7by the active filters.", "", "&eClick to open"),
                context -> context.openChild(() -> historyMenu.open(viewer, session)));
        builder.item(31, StatsMenuItems.item(Material.GOLD_INGOT, "&6Leaderboards",
                "&7Wins, matches, win rate", "&7and winning streaks.", "", "&eClick to open"),
                context -> context.openChild(() -> plugin.getLeaderboardMenu().openWithinNavigation(viewer)));
        builder.item(33, StatsMenuItems.item(Material.BARRIER, "&cClear Filters",
                "&7Reset arena, kits, opponent", "&7and time period."), context ->
        {
            session.clear();
            context.reopen();
        });

        configured.back().open(viewer);
    }

    private ItemStack profileHead(StatsPlayer target, PlayerStats result, StatsQuery filters)
    {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(target.id());
        meta.setOwningPlayer(owner);
        meta.displayName(StatsMenuItems.color("&a" + target.name()));

        List<Component> lore = new ArrayList<>();
        lore.add(StatsMenuItems.color("&7Matches: &f" + result.matches()));
        lore.add(StatsMenuItems.color("&7Wins: &a" + result.wins()));
        lore.add(StatsMenuItems.color("&7Losses: &c" + result.losses()));
        lore.add(StatsMenuItems.color(String.format(Locale.ROOT, "&7Win rate: &f%.1f%%", result.winRate())));
        lore.add(StatsMenuItems.color("&7Current streak: &f" + result.currentStreak()));
        lore.add(StatsMenuItems.color("&7Best streak: &f" + result.bestStreak()));
        lore.add(StatsMenuItems.color("&7Disconnect losses: &f" + result.disconnectLosses()));
        String average = result.matches() == 0 ? "N/A"
                : result.timedMatches() == 0 ? "No combat recorded"
                : StatsMenuItems.duration(result.averageCombatDurationMillis());
        lore.add(StatsMenuItems.color("&7Average fight: &f" + average));
        if (hasFilters(filters))
        {
            lore.add(Component.empty());
            lore.add(StatsMenuItems.color("&eFiltered results"));
        }
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private void openArenaFilter(Player viewer, StatsViewSession session)
    {
        List<FilterChoice> choices = new ArrayList<>();
        choices.add(new FilterChoice(null, "Any Arena", Material.COMPASS));
        for (Arena arena : plugin.getArenaManager().getArenas())
            choices.add(new FilterChoice(arena.getId(), arena.getName() + " (#" + arena.getId() + ")", Material.MAP));

        menus.paginatedMenu("stats-arena-filter", choices)
                .itemFactory(choice -> StatsMenuItems.item(choice.material(), "&e" + choice.name(),
                        choice.id() == null ? "&7Include every arena" : "&7Arena ID: &f" + choice.id(), "", "&eClick to select"))
                .onClick((context, choice) ->
                {
                    session.filters(session.filters().withArena(choice.id()));
                    context.back();
                })
                .back().open(viewer);
    }

    private void openKitFilter(Player viewer, StatsViewSession session, boolean opponent)
    {
        List<FilterChoice> choices = new ArrayList<>();
        choices.add(new FilterChoice(null, "Any Kit", Material.COMPASS));
        for (Kit kit : plugin.getKitManager().getKits())
            choices.add(new FilterChoice(kit.getId(), kit.getName() + " (#" + kit.getId() + ")",
                    kit.getIcon() != null ? kit.getIcon().getType() : Material.CHEST));

        menus.paginatedMenu(opponent ? "stats-opponent-kit-filter" : "stats-player-kit-filter", choices)
                .itemFactory(choice -> StatsMenuItems.item(choice.material(), "&e" + choice.name(),
                        choice.id() == null ? "&7Include every kit" : "&7Kit ID: &f" + choice.id(), "", "&eClick to select"))
                .onClick((context, choice) ->
                {
                    session.filters(opponent
                            ? session.filters().withOpponentKit(choice.id())
                            : session.filters().withPlayerKit(choice.id()));
                    context.back();
                })
                .back().open(viewer);
    }

    private void handleOpponent(MenuContext context, StatsViewSession session, Player viewer)
    {
        if (context.clickType().isRightClick())
        {
            java.util.UUID current = session.filters().opponentId();
            java.util.UUID quickOpponent = !viewer.getUniqueId().equals(session.target().id())
                    ? viewer.getUniqueId() : null;
            session.filters(session.filters().withOpponent(current == null ? quickOpponent : null));
            context.reopen();
            return;
        }

        context.requestInput(StatsMenuItems.color("&eType the opponent's name, or 'cancel'."), name ->
                stats.findPlayer(name).whenComplete((found, throwable) -> runSync(() ->
                {
                    if (!context.player().isOnline())
                        return;
                    if (throwable != null)
                    {
                        loadFailed(context.player(), throwable);
                        return;
                    }
                    if (found.isEmpty())
                    {
                        messages.send(context.player(), Message.STATS_PLAYER_NOT_FOUND, "player", name);
                        context.reopen();
                        return;
                    }
                    session.filters(session.filters().withOpponent(found.get().id()));
                    context.reopen();
                })), context::reopen);
    }

    private void requestId(MenuContext context, String label, java.util.function.Consumer<Integer> onSelect)
    {
        context.requestInput(StatsMenuItems.color("&eType the " + label + " ID, 'any' to clear, or 'cancel'."), input ->
        {
            if (input.equalsIgnoreCase("any"))
            {
                onSelect.accept(null);
                context.reopen();
                return;
            }

            try
            {
                int id = Integer.parseInt(input);
                if (id < 1)
                    throw new NumberFormatException();
                onSelect.accept(id);
            }
            catch (NumberFormatException exception)
            {
                context.player().sendMessage(StatsMenuItems.color("&cThat is not a valid positive ID."));
            }
            context.reopen();
        }, context::reopen);
    }

    private String arenaName(Integer id)
    {
        if (id == null)
            return "Any";
        Arena arena = plugin.getArenaManager().getArena(id);
        return arena != null ? arena.getName() + " (#" + id + ")" : "Deleted Arena #" + id;
    }

    private String kitName(Integer id)
    {
        if (id == null)
            return "Any";
        Kit kit = plugin.getKitManager().getKit(id);
        return kit != null ? kit.getName() + " (#" + id + ")" : "Deleted Kit #" + id;
    }

    private String opponentName(java.util.UUID id)
    {
        if (id == null)
            return "Any";
        Player online = Bukkit.getPlayer(id);
        if (online != null)
            return online.getName();
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString();
    }

    private static boolean hasFilters(StatsQuery query)
    {
        return query.opponentId() != null || query.playerKitId() != null || query.opponentKitId() != null
                || query.arenaId() != null || query.endedAfter() != null || query.endedBefore() != null;
    }

    private void loadFailed(Player player, Throwable throwable)
    {
        LOGGER.log(Level.WARNING, "Could not load duel statistics", throwable);
        messages.send(player, Message.STATS_LOAD_FAILED);
    }

    private void runSync(Runnable action)
    {
        if (plugin.isEnabled())
            plugin.core().tasks().runSync(action);
    }

    private record FilterChoice(Integer id, String name, Material material) {}
}
