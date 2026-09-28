package me.jackcw.duels.commands;

import me.jackcw.duels.Duels;
import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaSelection;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.challenge.ChallengeKind;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.menu.user.KitSelectorMenu;
import me.jackcw.duels.menu.user.ArenaSelectionMenu;
import me.jackcw.duels.menu.user.LeaderboardMenu;
import me.jackcw.duels.menu.user.SpectateMenu;
import me.jackcw.duels.menu.user.StatsProfileMenu;
import me.jackcw.duels.message.ActionMessenger;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.rematch.RematchContext;
import me.jackcw.duels.rematch.RematchManager;
import me.jackcw.duels.spectator.SpectateResult;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.jcore.command.ArgumentTypes;
import me.jackcw.jcore.command.CommandBuilder;
import me.jackcw.jcore.command.CommandContext;
import me.jackcw.jcore.command.CommandNode;
import me.jackcw.jcore.message.MessageManager;
import me.jackcw.jcore.menu.MenuManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class DuelCommand
{
    private final ChallengeManager challengeManager;
    private final RematchManager rematchManager;
    private final ArenaManager arenaManager;
    private final MatchManager matchManager;
    private final KitSelectorMenu kitSelectorMenu;
    private final ArenaSelectionMenu arenaSelectionMenu;
    private final LeaderboardMenu leaderboardMenu;
    private final SpectateMenu spectateMenu;
    private final StatsProfileMenu statsProfileMenu;
    private final SpectatorManager spectatorManager;
    private final MessageManager messageManager;
    private final ActionMessenger actionMessenger;
    private final DuelsSettings settings;
    private final MenuManager menus;

    public DuelCommand(Duels plugin)
    {
        this.challengeManager = plugin.getChallengeManager();
        this.rematchManager = plugin.getRematchManager();
        this.arenaManager = plugin.getArenaManager();
        this.matchManager = plugin.getMatchManager();
        this.kitSelectorMenu = plugin.getKitSelectorMenu();
        this.arenaSelectionMenu = new ArenaSelectionMenu(plugin);
        this.leaderboardMenu = plugin.getLeaderboardMenu();
        this.spectateMenu = plugin.getSpectateMenu();
        this.statsProfileMenu = plugin.getStatsProfileMenu();
        this.spectatorManager = plugin.getSpectatorManager();
        this.messageManager = plugin.core().messages();
        this.actionMessenger = plugin.getActionMessenger();
        this.settings = plugin.getSettings();
        this.menus = plugin.core().menus();
    }

    public CommandNode build()
    {
        return CommandBuilder.command("duel")
                .description("Challenge another player to a duel")
                .usage("/duel <player>")
                .permission("duels.duel")
                .alias("fight")
                .playerOnly()
                .optionalArgument("player", ArgumentTypes.player())
                .optionalArgument("arenaId", ArgumentTypes.integer())
                .executes(this::handleDuel)
                .child(
                        CommandBuilder.command("challenge")
                                .description("Challenge another player to a duel, even if their name matches a subcommand")
                                .usage("/duel challenge <player>")
                                .playerOnly()
                                .argument("player", ArgumentTypes.player())
                                .optionalArgument("arenaId", ArgumentTypes.integer())
                                .executes(this::handleDuel))
                .child(
                        CommandBuilder.command("select")
                                .description("Choose an arena in a menu before challenging a player")
                                .usage("/duel select <player>")
                                .playerOnly()
                                .argument("player", ArgumentTypes.player())
                                .executes(this::selectArena))
                .child(
                        CommandBuilder.command("accept")
                                .description("Accept a pending duel challenge")
                                .usage("/duel accept")
                                .optionalArgument("player", ArgumentTypes.player())
                                .executes(this::acceptDuel))
                .child(
                        CommandBuilder.command("deny")
                                .description("Decline a pending duel challenge")
                                .usage("/duel deny")
                                .optionalArgument("player", ArgumentTypes.player())
                                .executes(this::denyDuel))
                .child(
                        CommandBuilder.command("rematch")
                                .description("Ask the player you just duelled for another duel on the same arena")
                                .usage("/duel rematch")
                                .playerOnly()
                                .optionalArgument("player", ArgumentTypes.player())
                                .executes(this::rematchDuel))
                .child(
                        CommandBuilder.command("kit")
                                .description("Reopen the kit selection menu during kit selection")
                                .usage("/duel kit")
                                .playerOnly()
                                .executes(this::openKitSelector))
                .child(
                        CommandBuilder.command("spectate")
                                .description("Watch a live duel")
                                .usage("/duel spectate [player]")
                                .permission("duels.spectate")
                                .alias("watch")
                                .playerOnly()
                                .optionalArgument("player", ArgumentTypes.player())
                                .executes(this::spectateDuel))
                .child(
                        CommandBuilder.command("leave")
                                .description("Stop spectating a duel")
                                .usage("/duel leave")
                                .permission("duels.spectate")
                                .playerOnly()
                                .executes(this::leaveSpectating))
                .child(
                        CommandBuilder.command("stats")
                                .description("View detailed duel statistics")
                                .usage("/duel stats [player]")
                                .playerOnly()
                                .optionalArgument("player", ArgumentTypes.string())
                                .executes(this::openStats))
                .child(
                        CommandBuilder.command("top")
                                .description("View the top duelists by wins")
                                .usage("/duel top")
                                .playerOnly()
                                .executes(context -> leaderboardMenu.open(context.getPlayer())))
                .build();
    }

    private void openStats(CommandContext context)
    {
        Player viewer = context.getPlayer();
        if (context.has("player"))
            statsProfileMenu.openByName(viewer, context.get("player"));
        else
            statsProfileMenu.open(viewer);
    }

    private void handleDuel(CommandContext context)
    {
        Player sender = context.getPlayer();

        if (!context.has("player"))
        {
            actionMessenger.sendList(sender, Message.DUEL_HELP);
            return;
        }

        Player target = context.get("player");

        if (target.getUniqueId().equals(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.CHALLENGE_CANNOT_SELF);
            return;
        }

        if (matchManager.getMatch(sender.getUniqueId()) != null || matchManager.isPending(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.ALREADY_IN_MATCH);
            return;
        }

        if (matchManager.getMatch(target.getUniqueId()) != null || matchManager.isPending(target.getUniqueId()))
        {
            messageManager.send(sender, Message.TARGET_IN_MATCH, "player", target.getName());
            return;
        }

        ArenaSelection selection = ArenaSelection.any();
        if (context.has("arenaId"))
        {
            int arenaId = context.get("arenaId");
            if (arenaId < 1)
            {
                messageManager.send(sender, Message.NO_ARENA_AVAILABLE);
                return;
            }
            selection = ArenaSelection.specific(arenaId);
        }

        sendChallenge(sender, target, selection);
    }

    private void selectArena(CommandContext context)
    {
        Player sender = context.getPlayer();
        Player target = context.get("player");
        if (sender.getUniqueId().equals(target.getUniqueId()))
        {
            messageManager.send(sender, Message.CHALLENGE_CANNOT_SELF);
            return;
        }
        arenaSelectionMenu.open(sender, target.getName(), selection ->
        {
            Player currentTarget = Bukkit.getPlayer(target.getUniqueId());
            if (currentTarget == null || !currentTarget.isOnline())
            {
                messageManager.send(sender, Message.NO_ARENA_AVAILABLE);
                return;
            }
            sendChallenge(sender, currentTarget, selection);
        });
    }

    private void sendChallenge(Player sender, Player target, ArenaSelection selection)
    {
        if (!sender.isOnline() || !target.isOnline())
            return;
        if (matchManager.getMatch(sender.getUniqueId()) != null || matchManager.isPending(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.ALREADY_IN_MATCH);
            return;
        }
        if (matchManager.getMatch(target.getUniqueId()) != null || matchManager.isPending(target.getUniqueId()))
        {
            messageManager.send(sender, Message.TARGET_IN_MATCH, "player", target.getName());
            return;
        }
        Arena selected = selection.isAny() ? null : arenaManager.getArena(selection.arenaId());
        if (!selection.isAny() && (selected == null || !selected.isEnabled()))
        {
            messageManager.send(sender, Message.NO_ARENA_AVAILABLE);
            return;
        }

        Challenge existing = challengeManager.getChallengeBetween(sender.getUniqueId(), target.getUniqueId());
        if (existing != null && !existing.isRematch()
                && existing.getChallenged().equals(sender.getUniqueId()))
        {
            finishAccept(sender, existing);
            return;
        }

        // A direct challenge asks for new arena terms. It supersedes the old
        // rematch window and any invitation backed by that window.
        rematchManager.invalidateAll(sender.getUniqueId(), target.getUniqueId());
        challengeManager.removeRematchesInvolving(sender.getUniqueId(), target.getUniqueId());

        if (!challengeManager.createChallenge(sender, target, selection))
        {
            messageManager.send(sender, Message.CHALLENGE_ALREADY_PENDING, "player", target.getName());
            return;
        }

        String selectedArena = selection.isAny() ? "Any arena" : selected.getName();
        messageManager.send(sender, Message.CHALLENGE_SENT, "player", target.getName(), "expiry", expiryText(), "arena", selectedArena);
        actionMessenger.send(target, Message.CHALLENGE_RECEIVED, "player", sender.getName(), "arena", selectedArena);
    }

    /**
     * Asks for, or accepts, a rematch of the duel that just finished.
     *
     * <p>Deliberately the same request-or-accept shape as {@code /duel <player>}:
     * both players see the same button, and whichever of them clicks second is
     * accepting rather than sending a second request. Without that, two people
     * clicking at almost the same moment would each be told the other already
     * has a request pending, which is the least helpful possible answer to two
     * people who have just agreed to play again.
     *
     * <p>Once a request exists it is an ordinary challenge - claimed, expired
     * and removed by {@link ChallengeManager} like any other - and acceptance
     * goes through {@link #finishAccept}, so the asynchronous arena allocation
     * has exactly one implementation.
     */
    private void rematchDuel(CommandContext context)
    {
        Player sender = context.getPlayer();

        if (!rematchManager.isEnabled())
        {
            messageManager.send(sender, Message.REMATCH_DISABLED);
            return;
        }

        RematchContext rematch = rematchManager.get(sender.getUniqueId());

        // The button in a result message outlives its window. A click after the
        // window closed has to read as "that moment has passed", never as an
        // error, because there is nothing the player did wrong.
        if (rematch == null)
        {
            rematchManager.invalidate(sender.getUniqueId());
            messageManager.send(sender, Message.REMATCH_UNAVAILABLE);
            return;
        }

        UUID opponentId = rematch.opponentOf(sender.getUniqueId());
        String opponentName = rematch.opponentNameOf(sender.getUniqueId());

        if (context.has("player") && !((Player) context.get("player")).getUniqueId().equals(opponentId))
        {
            messageManager.send(sender, Message.REMATCH_WRONG_PLAYER, "player", opponentName);
            return;
        }

        Player opponent = Bukkit.getPlayer(opponentId);

        if (opponent == null || !opponent.isOnline())
        {
            rematchManager.invalidate(sender.getUniqueId());
            messageManager.send(sender, Message.REMATCH_OPPONENT_OFFLINE, "player", opponentName);
            return;
        }

        Challenge incoming = challengeManager.findIncoming(sender.getUniqueId(), opponentId);

        if (incoming != null && incoming.isRematch())
        {
            finishAccept(sender, incoming);
            return;
        }

        if (incoming != null)
        {
            messageManager.send(sender, Message.REMATCH_ALREADY_REQUESTED, "player", opponent.getName());
            return;
        }

        Arena arena = arenaManager.getArena(rematch.arenaId());

        // The arena can be deleted or disabled between the duel ending and the
        // click. Closing the window is better than silently falling back to a
        // different arena, because "the same arena" is the whole offer.
        if (arena == null || !arena.isEnabled())
        {
            rematchManager.invalidate(sender.getUniqueId());
            messageManager.send(sender, Message.REMATCH_ARENA_UNAVAILABLE);
            return;
        }

        if (matchManager.getMatch(sender.getUniqueId()) != null || matchManager.isPending(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.ALREADY_IN_MATCH);
            return;
        }

        if (matchManager.getMatch(opponentId) != null || matchManager.isPending(opponentId))
        {
            messageManager.send(sender, Message.TARGET_IN_MATCH, "player", opponent.getName());
            return;
        }

        if (!challengeManager.createChallenge(sender, opponent, ArenaSelection.specific(rematch.arenaId()),
                ChallengeKind.REMATCH, rematch.expiry()))
        {
            messageManager.send(sender, Message.REMATCH_ALREADY_REQUESTED, "player", opponent.getName());
            return;
        }

        messageManager.send(sender, Message.REMATCH_SENT, "player", opponent.getName(), "arena", arena.getName());
        actionMessenger.send(opponent, Message.REMATCH_RECEIVED,
                "player", sender.getName(), "arena", arena.getName());
    }

    private String expiryText()
    {
        int seconds = settings.challengeExpirySeconds();

        return seconds > 0 ? seconds + " seconds" : "never";
    }

    private void acceptDuel(CommandContext context)
    {
        Player sender = context.getPlayer();
        Challenge challenge;

        if (context.has("player"))
        {
            Player target = context.get("player");
            challenge = challengeManager.findIncoming(sender.getUniqueId(), target.getUniqueId());
        }
        else
            challenge = challengeManager.findIncoming(sender.getUniqueId());

        finishAccept(sender, challenge);
    }

    private void finishAccept(Player sender, Challenge challenge)
    {
        if (challenge == null)
        {
            actionMessenger.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        if (challenge.isRematch())
        {
            RematchContext rematch = rematchManager.get(sender.getUniqueId());
            if (!rematchManager.isEnabled() || rematch == null
                    || !challenge.getChallenger().equals(rematch.opponentOf(sender.getUniqueId()))
                    || !challenge.getSelection().equals(ArenaSelection.specific(rematch.arenaId())))
            {
                challengeManager.remove(challenge);
                messageManager.send(sender, Message.REMATCH_UNAVAILABLE);
                return;
            }

            Arena arena = arenaManager.getArena(rematch.arenaId());
            if (arena == null || !arena.isEnabled())
            {
                challengeManager.remove(challenge);
                rematchManager.invalidate(sender.getUniqueId());
                messageManager.send(sender, Message.REMATCH_ARENA_UNAVAILABLE);
                return;
            }
        }

        if (matchManager.getMatch(sender.getUniqueId()) != null || matchManager.isPending(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.ALREADY_IN_MATCH);
            return;
        }

        Player challenger = Bukkit.getPlayer(challenge.getChallenger());

        if (challenger == null)
        {
            actionMessenger.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        if (matchManager.getMatch(challenger.getUniqueId()) != null || matchManager.isPending(challenger.getUniqueId()))
        {
            messageManager.send(sender, Message.TARGET_IN_MATCH, "player", challenger.getName());
            return;
        }

        if (!challengeManager.claim(challenge))
        {
            actionMessenger.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        messageManager.send(sender, Message.PREPARING_ARENA);
        messageManager.send(challenger, Message.PREPARING_ARENA);
        matchManager.startMatchAsync(challenger, sender, challenge.getSelection()).whenComplete((result, throwable) ->
        {
            if (throwable != null || result == null || result.status() != me.jackcw.duels.match.MatchStartResult.Status.SUCCESS)
            {
                challengeManager.releaseClaim(challenge);
                Message failureMessage = result != null
                        && result.status() == me.jackcw.duels.match.MatchStartResult.Status.ARENA_CAPACITY_REACHED
                        ? Message.ARENA_CAPACITY_REACHED
                        : Message.NO_ARENA_AVAILABLE;
                if (sender.isOnline()) messageManager.send(sender, failureMessage);
                if (challenger.isOnline()) messageManager.send(challenger, failureMessage);
                return;
            }

            challengeManager.remove(challenge);
            messageManager.send(sender, challenge.isRematch() ? Message.REMATCH_ACCEPTED : Message.CHALLENGE_ACCEPTED,
                    "player", challenger.getName());
            messageManager.send(challenger,
                    challenge.isRematch() ? Message.REMATCH_ACCEPTED_OPPONENT : Message.CHALLENGE_ACCEPTED_OPPONENT,
                    "player", sender.getName());
            if (settings.removeOutstandingChallenges())
                challengeManager.removeAll(sender.getUniqueId(), challenger.getUniqueId());
        });
    }

    /**
     * With no argument this opens the live-match list, which is both the
     * discoverable entry point and the only sensible answer to "spectate what?".
     */
    private void spectateDuel(CommandContext context)
    {
        Player sender = context.getPlayer();

        if (!context.has("player"))
        {
            menus.open(sender, () -> spectateMenu.open(sender));
            return;
        }

        Player target = context.get("player");

        if (target.getUniqueId().equals(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.SPECTATE_CANNOT_SELF);
            return;
        }

        Match match = matchManager.getMatch(target.getUniqueId());

        if (match == null)
        {
            messageManager.send(sender, Message.SPECTATE_TARGET_NOT_IN_MATCH, "player", target.getName());
            return;
        }

        SpectateResult result = spectatorManager.start(sender, match);

        if (result != SpectateResult.SUCCESS)
        {
            messageManager.send(sender, SpectateMenu.messageFor(result));
            return;
        }

        actionMessenger.send(sender, Message.SPECTATE_STARTED, "player", target.getName());
    }

    private void leaveSpectating(CommandContext context)
    {
        Player sender = context.getPlayer();

        if (!spectatorManager.isSpectating(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.SPECTATE_NOT_SPECTATING);
            return;
        }

        if (!spectatorManager.stop(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.SPECTATE_TELEPORT_FAILED);
            return;
        }

        messageManager.send(sender, Message.SPECTATE_STOPPED);
    }

    private void openKitSelector(CommandContext context)
    {
        Player sender = context.getPlayer();
        Match match = matchManager.getMatch(sender.getUniqueId());

        if (match == null)
        {
            messageManager.send(sender, Message.NOT_IN_DUEL);
            return;
        }

        if (match.getState() != MatchState.PREGAME)
        {
            messageManager.send(sender, Message.KIT_SELECTION_CLOSED);
            return;
        }

        menus.open(sender, () -> kitSelectorMenu.open(sender));
    }

    private void denyDuel(CommandContext context)
    {
        Player sender = context.getPlayer();
        Challenge challenge;

        if (context.has("player"))
        {
            Player target = context.get("player");
            challenge = challengeManager.declineChallenge(sender.getUniqueId(), target.getUniqueId());
        }
        else
            challenge = challengeManager.declineChallenge(sender.getUniqueId());

        if (challenge == null)
        {
            actionMessenger.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        boolean senderWasChallenger = challenge.getChallenger().equals(sender.getUniqueId());
        UUID otherId = senderWasChallenger ? challenge.getChallenged() : challenge.getChallenger();
        Player other = Bukkit.getPlayer(otherId);

        if (other == null)
        {
            actionMessenger.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        Message declined = challenge.isRematch() ? Message.REMATCH_DECLINED : Message.CHALLENGE_DECLINED;
        Message declinedOpponent = challenge.isRematch()
                ? Message.REMATCH_DECLINED_OPPONENT
                : Message.CHALLENGE_DECLINED_OPPONENT;

        messageManager.send(senderWasChallenger ? other : sender, declined, "player", senderWasChallenger ? sender.getName() : other.getName());
        messageManager.send(senderWasChallenger ? sender : other, declinedOpponent, "player", senderWasChallenger ? other.getName() : sender.getName());
    }
}
