package me.jackcw.duels.spectator;

import me.jackcw.duels.match.Match;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * What a spectator was doing before they started spectating, plus which match
 * they are watching.
 *
 * <p>Everything except {@link #getMatch()} is persisted. The match itself is
 * runtime-only: it cannot outlive the server, and a session read back from disk
 * exists solely to undo a spectator's state, not to resume spectating.
 *
 * <p>Deliberately not built on {@code PlayerStateManager}. That system captures
 * a combatant's whole inventory/armour/health snapshot and holds one slot per
 * player, so sharing it would both do far more work than this needs and create
 * a class of bug where one subsystem's snapshot clobbers another's.
 */
public final class SpectatorSession
{
    private final UUID spectatorId;
    private final int arenaId;
    private final Location returnLocation;
    private final GameMode previousGameMode;
    private final boolean previousAllowFlight;
    private final boolean previousFlying;
    private final Match match;

    public SpectatorSession(UUID spectatorId, int arenaId, Location returnLocation, GameMode previousGameMode, boolean previousAllowFlight, boolean previousFlying, Match match)
    {
        this.spectatorId = spectatorId;
        this.arenaId = arenaId;
        this.returnLocation = returnLocation != null ? returnLocation.clone() : null;
        this.previousGameMode = previousGameMode;
        this.previousAllowFlight = previousAllowFlight;
        this.previousFlying = previousFlying;
        this.match = match;
    }

    public static SpectatorSession capture(Player player, Match match, int arenaId)
    {
        return new SpectatorSession(
                player.getUniqueId(),
                arenaId,
                player.getLocation(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                match
        );
    }

    public UUID getSpectatorId()
    {
        return spectatorId;
    }

    public int getArenaId()
    {
        return arenaId;
    }

    public Location getReturnLocation()
    {
        return returnLocation != null ? returnLocation.clone() : null;
    }

    public GameMode getPreviousGameMode()
    {
        return previousGameMode;
    }

    public boolean wasAllowedFlight()
    {
        return previousAllowFlight;
    }

    public boolean wasFlying()
    {
        return previousFlying;
    }

    /**
     * The match being watched, or {@code null} for a session read back from
     * disk after a restart - that match no longer exists.
     */
    public Match getMatch()
    {
        return match;
    }
}
