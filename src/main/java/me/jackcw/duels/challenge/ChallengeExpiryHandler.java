package me.jackcw.duels.challenge;

import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.UUID;

public final class ChallengeExpiryHandler
{
    private final MessageManager messageManager;

    public ChallengeExpiryHandler(MessageManager messageManager)
    {
        this.messageManager = messageManager;
    }

    public void onExpire(Challenge challenge)
    {
        Player challenger = Bukkit.getPlayer(challenge.getChallenger());
        Player challenged = Bukkit.getPlayer(challenge.getChallenged());

        if (challenger != null)
            messageManager.send(challenger, Message.CHALLENGE_EXPIRED, "player", nameOf(challenge.getChallenged()));

        if (challenged != null)
            messageManager.send(challenged, Message.CHALLENGE_EXPIRED, "player", nameOf(challenge.getChallenger()));
    }

    private String nameOf(UUID uuid)
    {
        Player online = Bukkit.getPlayer(uuid);

        if (online != null)
            return online.getName();

        OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);

        return offline.getName() != null ? offline.getName() : "that player";
    }
}
