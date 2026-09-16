package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.stats.LeaderboardEntry;
import me.jackcw.duels.stats.StatsManager;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.PaginatedMenu;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LeaderboardMenu
{
    private static final Logger LOGGER = Logger.getLogger(LeaderboardMenu.class.getName());
    private static final int TOP_PLAYERS_LIMIT = 10;
    private static final int YOUR_STATS_SLOT = 51;

    private final Duels plugin;
    private final MenuManager menus;
    private final StatsManager statsManager;
    private final MessageManager messageManager;

    public LeaderboardMenu(Duels plugin)
    {
        this.plugin = plugin;
        this.menus = plugin.getJCore().menus();
        this.statsManager = plugin.getStatsManager();
        this.messageManager = plugin.getJCore().messages();
    }

    public void open(Player player)
    {
        UUID playerId = player.getUniqueId();

        CompletableFuture<List<LeaderboardEntry>> topFuture = statsManager.getTopPlayers(TOP_PLAYERS_LIMIT);
        CompletableFuture<Integer> winsFuture = statsManager.getWins(playerId);
        CompletableFuture<Integer> lossesFuture = statsManager.getLosses(playerId);

        CompletableFuture.allOf(topFuture, winsFuture, lossesFuture)
                .thenRun(() -> plugin.getJCore().tasks().runSync(() ->
                {
                    if (!player.isOnline())
                        return;

                    render(player, topFuture.join(), winsFuture.join(), lossesFuture.join());
                }))
                .exceptionally(e ->
                {
                    LOGGER.log(Level.WARNING, "Could not load leaderboard stats", e);

                    if (plugin.isEnabled())
                    {
                        plugin.getJCore().tasks().runSync(() ->
                        {
                            if (player.isOnline())
                                messageManager.send(player, Message.STATS_LOAD_FAILED);
                        });
                    }

                    return null;
                });
    }

    private void render(Player player, List<LeaderboardEntry> topPlayers, int wins, int losses)
    {
        PaginatedMenu<LeaderboardEntry> menu = menus.paginatedMenu("stats-leaderboard", topPlayers)
                .itemFactory(this::playerHead)
                .onClick((context, entry) ->
                        messageManager.send(player, Message.PLAYER_RECORD, "player", nameOf(entry.playerId()), "wins", entry.wins()))
                .back()
                .build();

        menu.getMenu().setItem(YOUR_STATS_SLOT, yourStatsItem(wins, losses));
        menu.open(player);
    }

    private ItemStack playerHead(LeaderboardEntry entry)
    {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        Player online = Bukkit.getPlayer(entry.playerId());

        if (online != null)
            meta.setOwningPlayer(online);
        meta.displayName(color("&f" + nameOf(entry.playerId())));
        meta.lore(List.of(color("&7Wins: &a" + entry.wins()), Component.empty(), color("&eClick to view")));

        head.setItemMeta(meta);

        return head;
    }

    private ItemStack yourStatsItem(int wins, int losses)
    {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(color("&aYour Record"));
        meta.lore(List.of(color("&7Wins: &a" + wins), color("&7Losses: &c" + losses)));

        item.setItemMeta(meta);

        return item;
    }

    private String nameOf(UUID playerId)
    {
        Player online = Bukkit.getPlayer(playerId);

        if (online != null)
            return online.getName();

        OfflinePlayer offline = Bukkit.getOfflinePlayer(playerId);

        return offline.getName() != null ? offline.getName() : "Unknown";
    }

    private Component color(String text)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
