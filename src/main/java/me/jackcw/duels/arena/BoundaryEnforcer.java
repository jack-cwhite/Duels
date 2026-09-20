package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns arena boundary state and enforcement for players in an active match.
 *
 * <p>Movement tells us when a player <em>crosses</em> the boundary, but a
 * grace period is a condition on elapsed time and no event fires for "three
 * seconds have passed". Detection therefore lives on the move event while
 * deadlines are evaluated by a scheduled check, so a player who walks out and
 * stands still is still enforced against.
 */
public final class BoundaryEnforcer
{
    private static final long CHECK_PERIOD_TICKS = 10L;

    private final Duels plugin;
    private final MessageManager messageManager;
    private final MatchManager matchManager;
    private final ArenaManager arenaManager;

    private final Map<UUID, Long> outOfBoundsSince = new HashMap<>();
    private final Map<UUID, Location> lastInBoundsLocation = new HashMap<>();

    private BukkitTask checkTask;

    public BoundaryEnforcer(Duels plugin)
    {
        this.plugin = plugin;
        this.messageManager = plugin.core().messages();
        this.matchManager = plugin.getMatchManager();
        this.arenaManager = plugin.getArenaManager();
    }

    public void handleMove(PlayerMoveEvent event)
    {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()))
            return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Match match = matchManager.getMatch(uuid);

        if (match == null || match.getState() != MatchState.IN_PROGRESS)
        {
            forget(uuid);
            return;
        }

        Arena arena = arenaManager.getArena(match.getArenaInstance().getArenaId());

        if (arena == null || !arena.hasBounds())
            return;

        if (isWithinBounds(to, arena))
        {
            clearOutOfBounds(uuid);
            lastInBoundsLocation.put(uuid, to.clone());
            return;
        }

        markOutOfBounds(uuid, player);

        // With no grace period a soft return should feel like a wall, so it is
        // applied here rather than waiting for the next scheduled check. The
        // destination is replaced via setTo instead of cancelling the event and
        // teleporting: cancelling means "reject this move and put the player
        // back at getFrom()", which is itself outside the bounds, and issuing a
        // teleport alongside it leaves two repositions competing inside one
        // movement packet. Every other case is left to the scheduled check.
        if (arena.getBoundaryMode() != BoundaryMode.SOFT_RETURN || arena.getGraceSeconds() > 0)
            return;

        event.setTo(safeLocation(uuid, match));
        clearOutOfBounds(uuid);
        messageManager.send(player, Message.OUT_OF_BOUNDS_RETURNED);
    }

    public void forget(UUID uuid)
    {
        clearOutOfBounds(uuid);
        lastInBoundsLocation.remove(uuid);
    }

    public void shutdown()
    {
        outOfBoundsSince.clear();
        lastInBoundsLocation.clear();
        stopTask();
    }

    private void markOutOfBounds(UUID uuid, Player player)
    {
        if (outOfBoundsSince.containsKey(uuid))
            return;

        outOfBoundsSince.put(uuid, System.currentTimeMillis());
        messageManager.send(player, Message.OUT_OF_BOUNDS_WARNING);

        startTask();
    }

    private void clearOutOfBounds(UUID uuid)
    {
        outOfBoundsSince.remove(uuid);
    }

    private void tick()
    {
        // The task only exists while somebody is outside the bounds, so it
        // retires itself rather than idling over an empty map for the rest of
        // the server's uptime.
        if (outOfBoundsSince.isEmpty())
        {
            stopTask();
            return;
        }

        for (UUID uuid : new ArrayList<>(outOfBoundsSince.keySet()))
            tickPlayer(uuid);
    }

    private void tickPlayer(UUID uuid)
    {
        Player player = plugin.getServer().getPlayer(uuid);
        Match match = matchManager.getMatch(uuid);

        if (player == null || !player.isOnline() || match == null || match.getState() != MatchState.IN_PROGRESS)
        {
            forget(uuid);
            return;
        }

        Arena arena = arenaManager.getArena(match.getArenaInstance().getArenaId());

        if (arena == null || !arena.hasBounds())
        {
            forget(uuid);
            return;
        }

        Location location = player.getLocation();

        // An admin can move the bounds or change the mode mid-match, so both
        // are re-read every check instead of being captured when the player
        // first left.
        if (isWithinBounds(location, arena))
        {
            clearOutOfBounds(uuid);
            lastInBoundsLocation.put(uuid, location.clone());
            return;
        }

        BoundaryMode mode = arena.getBoundaryMode();

        if (mode == BoundaryMode.WARNING)
            return;

        long elapsed = System.currentTimeMillis() - outOfBoundsSince.get(uuid);

        if (elapsed < arena.getGraceSeconds() * 1000L)
            return;

        if (mode == BoundaryMode.FORFEIT)
        {
            forget(uuid);
            matchManager.endMatch(match, match.getOpponent(uuid));
            return;
        }

        // Deliberately cleared only once the teleport has succeeded. Clearing
        // first would let a failed return look like the player had only just
        // stepped out, handing them a fresh grace period on every attempt.
        if (!player.teleport(safeLocation(uuid, match)))
            return;

        clearOutOfBounds(uuid);
        messageManager.send(player, Message.OUT_OF_BOUNDS_RETURNED);
    }

    private Location safeLocation(UUID uuid, Match match)
    {
        Location safe = lastInBoundsLocation.get(uuid);

        if (safe != null)
            return safe.clone();

        // Not Match#getLocation - that is where the player stood before the
        // duel began, so returning them there would eject them from the arena
        // entirely rather than putting them back inside it.
        ArenaInstance instance = match.getArenaInstance();

        return uuid.equals(match.getPlayer1Id()) ? instance.getSpawn1() : instance.getSpawn2();
    }

    private boolean isWithinBounds(Location location, Arena arena)
    {
        Location corner1 = arena.getBoundsCorner1();
        Location corner2 = arena.getBoundsCorner2();

        // A player who is not even in the arena's world cannot be inside its
        // bounds. Only matches that are IN_PROGRESS reach this check, and a
        // participant is removed from the match before end-of-match restoration
        // teleports them out, so this does not fire during normal cleanup.
        if (!location.getWorld().equals(corner1.getWorld()))
            return false;

        double minX = Math.min(corner1.getX(), corner2.getX());
        double maxX = Math.max(corner1.getX(), corner2.getX());
        double minY = Math.min(corner1.getY(), corner2.getY());
        double maxY = Math.max(corner1.getY(), corner2.getY());
        double minZ = Math.min(corner1.getZ(), corner2.getZ());
        double maxZ = Math.max(corner1.getZ(), corner2.getZ());

        return location.getX() >= minX && location.getX() <= maxX
                && location.getY() >= minY && location.getY() <= maxY
                && location.getZ() >= minZ && location.getZ() <= maxZ;
    }

    private void startTask()
    {
        if (checkTask != null)
            return;

        checkTask = plugin.core().tasks().runSyncTimer(this::tick, CHECK_PERIOD_TICKS, CHECK_PERIOD_TICKS);
    }

    private void stopTask()
    {
        if (checkTask == null)
            return;

        checkTask.cancel();
        checkTask = null;
    }
}
