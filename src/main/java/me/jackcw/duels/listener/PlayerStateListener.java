package me.jackcw.duels.listener;

import me.jackcw.duels.Duels;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.player.PlayerStateManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerStateListener implements Listener
{
    private final MessageManager messageManager;
    private final PlayerStateManager playerStateManager;

    public PlayerStateListener(Duels plugin)
    {
        this.messageManager = plugin.core().messages();
        this.playerStateManager = plugin.getPlayerStateManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();

        if (playerStateManager.has(player) && playerStateManager.restore(player))
            messageManager.send(player, Message.STATE_RESTORED);
    }
}
