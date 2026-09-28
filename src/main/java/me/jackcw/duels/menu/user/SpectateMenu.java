package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.ActionMessenger;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.spectator.SpectateResult;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SpectateMenu
{
    private final MenuManager menus;
    private final MatchManager matchManager;
    private final SpectatorManager spectatorManager;
    private final ArenaManager arenaManager;
    private final MessageManager messageManager;
    private final ActionMessenger actionMessenger;

    public SpectateMenu(Duels plugin)
    {
        this.menus = plugin.core().menus();
        this.matchManager = plugin.getMatchManager();
        this.spectatorManager = plugin.getSpectatorManager();
        this.arenaManager = plugin.getArenaManager();
        this.messageManager = plugin.core().messages();
        this.actionMessenger = plugin.getActionMessenger();
    }

    public void open(Player player)
    {
        List<Match> live = matchManager.getActiveMatches();

        if (live.isEmpty())
        {
            actionMessenger.send(player, Message.SPECTATE_NO_MATCHES);
            return;
        }

        menus.paginatedMenu("spectate-matches", live)
                .itemFactory(this::matchIcon)
                .onClick((context, match) -> handleClick(player, match))
                .back()
                .build()
                .open(player);
    }

    /**
     * The menu was rendered from a snapshot taken when it opened, so the match
     * behind a clicked item may have finished in the meantime. Everything is
     * therefore re-validated through {@code SpectatorManager} rather than
     * trusted from the item.
     */
    private void handleClick(Player player, Match match)
    {
        SpectateResult result = spectatorManager.start(player, match);

        if (result != SpectateResult.SUCCESS)
        {
            messageManager.send(player, messageFor(result));
            return;
        }

        player.closeInventory();

        actionMessenger.send(player, Message.SPECTATE_STARTED, "player", nameOf(match.getPlayer1Id()));
    }

    public static Message messageFor(SpectateResult result)
    {
        return switch (result)
        {
            case ALREADY_IN_MATCH -> Message.SPECTATE_WHILE_IN_MATCH;
            case ALREADY_SPECTATING -> Message.SPECTATE_ALREADY;
            case NO_BOUNDS -> Message.SPECTATE_ARENA_NO_BOUNDS;
            case TELEPORT_FAILED -> Message.SPECTATE_TELEPORT_FAILED;
            default -> Message.SPECTATE_MATCH_UNAVAILABLE;
        };
    }

    private ItemStack matchIcon(Match match)
    {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();

        Player player1 = Bukkit.getPlayer(match.getPlayer1Id());

        if (player1 != null)
            meta.setOwningPlayer(player1);

        Arena arena = arenaManager.getArena(match.getArenaInstance().getArenaId());

        List<Component> lore = new ArrayList<>();
        lore.add(color("&7Arena: &f" + (arena != null ? arena.getName() : "Unknown")));
        lore.add(color("&7Status: &f" + describe(match.getState())));
        lore.add(color("&7Running for: &f" + formatDuration(System.currentTimeMillis() - match.getStartedAt())));
        lore.add(color("&7Watching: &f" + spectatorManager.spectatorsOf(match).size()));
        lore.add(Component.empty());
        lore.add(color("&eClick to spectate"));

        meta.displayName(color("&f" + nameOf(match.getPlayer1Id()) + " &7vs &f" + nameOf(match.getPlayer2Id())));
        meta.lore(lore);

        head.setItemMeta(meta);

        return head;
    }

    private String describe(MatchState state)
    {
        return switch (state)
        {
            case PREGAME -> "Selecting kits";
            case GRACE -> "Grace period";
            case IN_PROGRESS -> "Fighting";
            case ENDED -> "Finished";
        };
    }

    private String formatDuration(long millis)
    {
        long totalSeconds = Math.max(0L, millis / 1000L);

        return String.format("%d:%02d", totalSeconds / 60, totalSeconds % 60);
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
