package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchConclusion;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.duels.spectator.SpectatorSession;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns arena boundary state and enforcement for players in an active match, and
 * for the spectators watching one.
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

    /**
     * Which arena is currently keeping a player in, and under what policy.
     *
     * <p>Combatants and spectators answer this differently, and the answer is
     * needed in both the move handler and the scheduled check, so it is worked
     * out in one place rather than branched on twice.
     */
    private record Enforcement(Match match, ArenaInstance instance, BoundaryMode mode, int graceSeconds, boolean spectator) {}

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
        Enforcement enforcement = resolve(uuid);

        if (enforcement == null)
        {
            forget(uuid);
            return;
        }

        if (enforcement.instance().contains(to))
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
        if (enforcement.mode() != BoundaryMode.SOFT_RETURN || enforcement.graceSeconds() > 0)
            return;

        event.setTo(safeLocation(uuid, enforcement));
        clearOutOfBounds(uuid);
        messageManager.send(player, Message.OUT_OF_BOUNDS_RETURNED);
    }

    public void forget(UUID uuid)
    {
        clearOutOfBounds(uuid);
        lastInBoundsLocation.remove(uuid);
    }

    /**
     * How many players this enforcer is still remembering. Both maps are keyed
     * by player and are only meant to hold entries for duellists currently in a
     * match, so a non-zero count with no live match is a leak.
     */
    public int getTrackedPlayerCount()
    {
        Set<UUID> tracked = new HashSet<>(outOfBoundsSince.keySet());
        tracked.addAll(lastInBoundsLocation.keySet());

        return tracked.size();
    }

    /**
     * Whether the periodic check task is currently registered with the
     * scheduler. Reported by {@code /duels diagnostics} so a leaked task can
     * be told apart from the many other things "Scheduled plugin tasks" could
     * mean - this is the one that would keep running forever if something ever
     * stopped {@link #outOfBoundsSince} from emptying.
     */
    public boolean isCheckTaskActive()
    {
        return checkTask != null;
    }

    public void shutdown()
    {
        outOfBoundsSince.clear();
        lastInBoundsLocation.clear();
        stopTask();
    }

    private Enforcement resolve(UUID uuid)
    {
        Match match = matchManager.getMatch(uuid);

        if (match != null)
        {
            if (match.getState() != MatchState.IN_PROGRESS)
                return null;

            ArenaInstance instance = boundedInstance(match);

            if (instance == null)
                return null;

            Arena arena = arenaManager.getArena(instance.getArenaId());

            return arena == null ? null : new Enforcement(match, instance, arena.getBoundaryMode(), arena.getGraceSeconds(), false);
        }

        SpectatorManager spectatorManager = plugin.getSpectatorManager();
        SpectatorSession session = spectatorManager != null ? spectatorManager.getSession(uuid) : null;

        if (session == null || session.getMatch() == null || session.getMatch().getState() == MatchState.ENDED)
            return null;

        ArenaInstance instance = boundedInstance(session.getMatch());

        if (instance == null)
            return null;

        // A spectator is held to the bounds throughout PREGAME and GRACE too,
        // not just IN_PROGRESS - there is a fight to watch from the moment they
        // arrive. The arena's configured mode deliberately does not apply to
        // them: FORFEIT is meaningless for somebody who is not fighting, and
        // WARNING would let them drift into a neighbouring arena while the
        // plugin still believes they are watching this one. They are always
        // walled in immediately.
        return new Enforcement(session.getMatch(), instance, BoundaryMode.SOFT_RETURN, 0, true);
    }

    private ArenaInstance boundedInstance(Match match)
    {
        ArenaInstance instance = match.getArenaInstance();

        return instance.hasBounds() ? instance : null;
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
        Enforcement enforcement = resolve(uuid);

        if (player == null || !player.isOnline() || enforcement == null)
        {
            forget(uuid);
            return;
        }

        Location location = player.getLocation();

        // An admin can move the bounds or change the mode mid-match, so both
        // are re-read every check instead of being captured when the player
        // first left.
        if (enforcement.instance().contains(location))
        {
            clearOutOfBounds(uuid);
            lastInBoundsLocation.put(uuid, location.clone());
            return;
        }

        if (enforcement.mode() == BoundaryMode.WARNING)
            return;

        long elapsed = System.currentTimeMillis() - outOfBoundsSince.get(uuid);

        if (elapsed < enforcement.graceSeconds() * 1000L)
            return;

        if (enforcement.mode() == BoundaryMode.FORFEIT)
        {
            forget(uuid);
            matchManager.endMatch(enforcement.match(),
                    MatchConclusion.boundaryForfeit(enforcement.match().getOpponent(uuid)));
            return;
        }

        // Deliberately cleared only once the teleport has succeeded. Clearing
        // first would let a failed return look like the player had only just
        // stepped out, handing them a fresh grace period on every attempt.
        if (!player.teleport(safeLocation(uuid, enforcement)))
            return;

        clearOutOfBounds(uuid);
        messageManager.send(player, Message.OUT_OF_BOUNDS_RETURNED);
    }

    private Location safeLocation(UUID uuid, Enforcement enforcement)
    {
        Location safe = lastInBoundsLocation.get(uuid);

        if (safe != null)
            return safe.clone();

        // Not Match#getLocation - that is where the player stood before the
        // duel began, so returning them there would eject them from the arena
        // entirely rather than putting them back inside it.
        ArenaInstance instance = enforcement.match().getArenaInstance();

        if (enforcement.spectator())
            return instance.getSpawn1();

        return uuid.equals(enforcement.match().getPlayer1Id()) ? instance.getSpawn1() : instance.getSpawn2();
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
