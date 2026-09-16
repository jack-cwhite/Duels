package me.jackcw.duels.commands;

import me.jackcw.duels.Duels;
import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.menu.user.KitSelectorMenu;
import me.jackcw.duels.menu.user.LeaderboardMenu;
import me.jackcw.duels.message.Message;
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
    private final MatchManager matchManager;
    private final KitSelectorMenu kitSelectorMenu;
    private final LeaderboardMenu leaderboardMenu;
    private final MessageManager messageManager;
    private final DuelsSettings settings;
    private final MenuManager menus;

    public DuelCommand(Duels plugin)
    {
        this.challengeManager = plugin.getChallengeManager();
        this.matchManager = plugin.getMatchManager();
        this.kitSelectorMenu = plugin.getKitSelectorMenu();
        this.leaderboardMenu = plugin.getLeaderboardMenu();
        this.messageManager = plugin.getJCore().messages();
        this.settings = plugin.getSettings();
        this.menus = plugin.getJCore().menus();
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
                .executes(this::handleDuel)
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
                        CommandBuilder.command("kit")
                                .description("Reopen the kit selection menu during kit selection")
                                .usage("/duel kit")
                                .playerOnly()
                                .executes(this::openKitSelector))
                .child(
                        CommandBuilder.command("top")
                                .description("View the top duelists by wins")
                                .usage("/duel top")
                                .playerOnly()
                                .executes(context -> leaderboardMenu.open(context.getPlayer())))
                .build();
    }

    private void handleDuel(CommandContext context)
    {
        Player sender = context.getPlayer();

        if (!context.has("player"))
        {
            messageManager.sendList(sender, Message.DUEL_HELP);
            return;
        }

        Player target = context.get("player");

        if (target.getUniqueId().equals(sender.getUniqueId()))
        {
            messageManager.send(sender, Message.CHALLENGE_CANNOT_SELF);
            return;
        }

        if (matchManager.getMatch(sender.getUniqueId()) != null)
        {
            messageManager.send(sender, Message.ALREADY_IN_MATCH);
            return;
        }

        if (matchManager.getMatch(target.getUniqueId()) != null)
        {
            messageManager.send(sender, Message.TARGET_IN_MATCH, "player", target.getName());
            return;
        }

        Challenge existing = challengeManager.getChallengeBetween(sender.getUniqueId(), target.getUniqueId());

        if (existing != null && existing.getChallenged().equals(sender.getUniqueId()))
        {
            Challenge accepted = challengeManager.acceptChallenge(sender.getUniqueId(), target.getUniqueId());
            finishAccept(sender, accepted);
            return;
        }

        if (!challengeManager.createChallenge(sender, target))
        {
            messageManager.send(sender, Message.CHALLENGE_ALREADY_PENDING, "player", target.getName());
            return;
        }

        messageManager.send(sender, Message.CHALLENGE_SENT, "player", target.getName(), "expiry", expiryText());
        messageManager.send(target, Message.CHALLENGE_RECEIVED, "player", sender.getName());
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
            challenge = challengeManager.acceptChallenge(sender.getUniqueId(), target.getUniqueId());
        }
        else
            challenge = challengeManager.acceptChallenge(sender.getUniqueId());

        finishAccept(sender, challenge);
    }

    private void finishAccept(Player sender, Challenge challenge)
    {
        if (challenge == null)
        {
            messageManager.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        if (matchManager.getMatch(sender.getUniqueId()) != null)
        {
            messageManager.send(sender, Message.ALREADY_IN_MATCH);
            return;
        }

        Player challenger = Bukkit.getPlayer(challenge.getChallenger());

        if (challenger == null)
        {
            messageManager.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        if (matchManager.getMatch(challenger.getUniqueId()) != null)
        {
            messageManager.send(sender, Message.TARGET_IN_MATCH, "player", challenger.getName());
            return;
        }

        messageManager.send(sender, Message.CHALLENGE_ACCEPTED, "player", challenger.getName());
        messageManager.send(challenger, Message.CHALLENGE_ACCEPTED_OPPONENT, "player", sender.getName());

        Match match = matchManager.startMatch(challenger, sender);

        if (match == null)
        {
            messageManager.send(sender, Message.NO_ARENA_AVAILABLE);
            messageManager.send(challenger, Message.NO_ARENA_AVAILABLE);
        }
        else
            if (settings.removeOutstandingChallenges())
                challengeManager.removeAll(sender.getUniqueId(), challenger.getUniqueId());
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

        if (match.getState() != MatchState.KIT_SELECTION)
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
            messageManager.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        boolean senderWasChallenger = challenge.getChallenger().equals(sender.getUniqueId());
        UUID otherId = senderWasChallenger ? challenge.getChallenged() : challenge.getChallenger();
        Player other = Bukkit.getPlayer(otherId);

        if (other == null)
        {
            messageManager.send(sender, Message.CHALLENGE_NO_PENDING);
            return;
        }

        messageManager.send(senderWasChallenger ? other : sender, Message.CHALLENGE_DECLINED, "player", senderWasChallenger ? sender.getName() : other.getName());
        messageManager.send(senderWasChallenger ? sender : other, Message.CHALLENGE_DECLINED_OPPONENT, "player", senderWasChallenger ? other.getName() : sender.getName());
    }
}
