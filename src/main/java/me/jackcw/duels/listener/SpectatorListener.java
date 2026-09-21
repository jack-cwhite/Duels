package me.jackcw.duels.listener;

import com.destroystokyo.paper.event.player.PlayerStartSpectatingEntityEvent;
import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.duels.spectator.SpectatorSession;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.UUID;

public final class SpectatorListener implements Listener
{
    private final SpectatorManager spectatorManager;

    public SpectatorListener(Duels plugin)
    {
        this.spectatorManager = plugin.getSpectatorManager();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event)
    {
        spectatorManager.restoreOnJoin(event.getPlayer());
    }

    /**
     * In spectator mode, clicking an entity locks the camera to it and the
     * player's position <em>follows</em> that entity. A combatant knocked out of
     * the arena would therefore drag a locked spectator out with them, straight
     * past the move-event enforcement that would otherwise wall them in.
     *
     * <p>Restricting the target to the two combatants closes that, and doubles
     * as the feature players expect anyway: click a fighter to follow them.
     */
    @EventHandler(ignoreCancelled = true)
    public void onStartSpectatingEntity(PlayerStartSpectatingEntityEvent event)
    {
        SpectatorSession session = spectatorManager.getSession(event.getPlayer().getUniqueId());

        if (session == null || session.getMatch() == null)
            return;

        if (!isCombatant(session.getMatch(), event.getNewSpectatorTarget()))
            event.setCancelled(true);
    }

    private boolean isCombatant(Match match, Entity target)
    {
        if (!(target instanceof Player player))
            return false;

        UUID uuid = player.getUniqueId();

        return uuid.equals(match.getPlayer1Id()) || uuid.equals(match.getPlayer2Id());
    }
}
