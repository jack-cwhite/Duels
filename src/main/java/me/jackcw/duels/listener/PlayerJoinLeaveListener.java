package me.jackcw.duels.listener;

import me.jackcw.duels.Duels;
import me.jackcw.duels.challenge.Challenge;
import me.jackcw.duels.challenge.ChallengeManager;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.List;
import java.util.UUID;

public final class PlayerJoinLeaveListener implements Listener
{
    private final ChallengeManager challengeManager;
    private final MessageManager messageManager;
    private final PlayerStateManager playerStateManager;

    public PlayerJoinLeaveListener(Duels plugin)
    {
        this.challengeManager = plugin.getChallengeManager();
        this.messageManager = plugin.getJCore().messages();
        this.playerStateManager = plugin.getPlayerStateManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();

        if (playerStateManager.has(player) && playerStateManager.restore(player))
            messageManager.send(player, Message.STATE_RESTORED);
    }


    @EventHandler
    public void onPlayerLeave(PlayerQuitEvent event)
    {
        UUID uuid = event.getPlayer().getUniqueId();
        List<Challenge> challenges = challengeManager.getChallenges(uuid);

        if (challenges.isEmpty())
            return;

        for (Challenge challenge : challenges)
        {
            challengeManager.remove(challenge);

            boolean leaverWasChallenger = challenge.getChallenger().equals(uuid);
            UUID otherId = leaverWasChallenger ? challenge.getChallenged() : challenge.getChallenger();
            Player other = Bukkit.getPlayer(otherId);

            if (other != null)
                messageManager.send(other, Message.CHALLENGE_CANCELLED_DISCONNECT, "player", event.getPlayer().getName());
        }
    }
}
