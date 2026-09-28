package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.duels.spectator.SpectatorManager;
import me.jackcw.duels.spectator.SpectatorSession;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Keeps anyone who is not fighting or spectating a match out of the bounds
 * of a match that is actually happening there.
 *
 * <p>{@link BoundaryEnforcer} keeps a combatant or spectator <em>inside</em>
 * their own arena. This is the other half: a bystander walking, teleporting,
 * or being pushed into an arena's bounds while a duel is live there (or its
 * rollback has not finished) is turned away the same way, so a duel's
 * hazards can never reach someone who never agreed to be in one.
 *
 * <p>Enforcement is deliberately scoped to {@link MatchManager#getUnsafeInstanceAt}
 * rather than to any instance with bounds configured: an idle static arena is
 * ordinary ground the moment no match holds it, since arena editing and simply
 * walking around a static arena between fights both depend on that. Only an
 * instance a duel is actually using - live or still resetting - is off-limits.
 *
 * <p>That split creates two distinct situations, handled differently on
 * purpose. Someone <em>entering</em> is stopped where they are, since where
 * they came from was outside by definition. Someone already <em>inside</em>
 * when the arena became off-limits cannot be stopped in place - that just
 * pins them in the duel - so they are moved out instead.
 */
public final class ArenaAccessGuard implements Listener
{
    private static final Logger LOGGER = Logger.getLogger(ArenaAccessGuard.class.getName());

    private static final long DENIED_MESSAGE_COOLDOWN_MS = 2_000L;

    private final MatchManager matchManager;
    private final SpectatorManager spectatorManager;
    private final ArenaInstanceManager arenaInstanceManager;
    private final MessageManager messageManager;
    private final DuelsSettings settings;

    private final Map<UUID, Long> lastDeniedMessageAt = new HashMap<>();

    public ArenaAccessGuard(Duels plugin)
    {
        this.matchManager = plugin.getMatchManager();
        this.spectatorManager = plugin.getSpectatorManager();
        this.arenaInstanceManager = plugin.getArenaInstanceManager();
        this.messageManager = plugin.core().messages();
        this.settings = plugin.getSettings();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMove(PlayerMoveEvent event)
    {
        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null || (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()))
            return;

        ArenaInstance instance = matchManager.getUnsafeInstanceAt(to);

        if (instance == null)
            return;

        Player player = event.getPlayer();

        if (isOccupantOf(player, instance))
            return;

        // Inside already, rather than walking in: cancelling a move that both
        // starts and ends in the arena would pin the player in the middle of
        // the duel and deny every attempt they made to walk out of it, so the
        // only correct answer is to take them out.
        if (instance.contains(from))
        {
            removeFromArena(player, instance);
            return;
        }

        event.setCancelled(true);
        sendDeniedMessage(player);
    }

    /**
     * Covers entry that does not arrive through {@link PlayerMoveEvent} at
     * all - a command teleport, a warp, an ender pearl. Cancelling rather
     * than redirecting leaves the player exactly where they already were,
     * matching how a denied block place or break behaves elsewhere in this
     * package.
     *
     * <p>A {@link PlayerTeleportEvent.TeleportCause#PLUGIN} teleport is never
     * blocked. That cause means a plugin chose this destination deliberately
     * rather than a player walking, warping, or throwing a pearl into it, and
     * Duels' own restorations are exactly this case - a spectator returned to
     * wherever they stood before they started watching can land back inside
     * some arena's bounds even though they are not that arena's occupant, and
     * second-guessing a location this system already decided was correct
     * would just make legitimate restorations fail.
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onTeleport(PlayerTeleportEvent event)
    {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN)
            return;

        Location to = event.getTo();

        if (to == null)
            return;

        Player player = event.getPlayer();
        ArenaInstance instance = matchManager.getUnsafeInstanceAt(to);

        if (instance == null || isOccupantOf(player, instance))
            return;

        event.setCancelled(true);
        sendDeniedMessage(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event)
    {
        lastDeniedMessageAt.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Clears a fresh match's instance of anyone still standing in its bounds
     * before the two combatants are teleported in.
     *
     * <p>This is the primary way a bystander is ever removed, not a rare
     * backstop: an idle arena is ordinary ground {@link #onMove} and
     * {@link #onTeleport} never restrict, precisely so arena editing and
     * casual walking through a static arena between fights both keep working.
     * The moment a match actually claims the instance, this sweep is what
     * moves anyone left standing in it out, and continuous enforcement takes
     * over from there for as long as the instance stays unsafe.
     */
    public void evictBystanders(ArenaInstance instance, UUID player1Id, UUID player2Id)
    {
        if (!instance.hasBounds())
            return;

        Set<UUID> exempt = Set.of(player1Id, player2Id);

        for (Player player : Bukkit.getOnlinePlayers())
        {
            if (exempt.contains(player.getUniqueId()) || !instance.contains(player.getLocation()))
                continue;

            removeFromArena(player, instance);
        }
    }

    private void removeFromArena(Player player, ArenaInstance instance)
    {
        player.teleport(exitLocation(player, instance));
        messageManager.send(player, Message.REMOVED_FROM_ARENA);
    }

    /**
     * Where someone taken out of an arena is sent.
     *
     * <p>A world spawn, rather than wherever the player last stood: they were
     * inside the bounds, so none of their recent positions is known to be
     * outside them. An earlier version cached a "last safe location" per
     * player instead, which broke the moment idle arenas became ordinary
     * ground - walking around an idle arena cached positions inside it, so a
     * match starting there evicted the player to where they already were and
     * then denied every move they made out of it.
     *
     * <p>{@link DuelsSettings#fallbackSpawn()} chooses between the configured
     * world and the main one. Deliberately not the player's own world, even
     * though staying in-world reads as the friendlier choice: arenas are
     * commonly kept in a dedicated void world, and that world's spawn point is
     * a hole.
     *
     * <p>If that spawn is not usable either - an admin has built arenas over
     * it - the player is put down just beyond the edge of the arena they are
     * being taken out of, because leaving them inside a live duel is the one
     * outcome that is definitely wrong.
     */
    private Location exitLocation(Player player, ArenaInstance instance)
    {
        Location fallback = settings.fallbackSpawn();

        if (!insideAnyBounds(fallback))
            return fallback;

        LOGGER.warning("Arena bounds cover the spawn Duels would have moved '" + player.getName()
                + "' to, so they were put at the edge of arena instance " + instance.getId()
                + " instead; set 'fallback-world' to a world whose spawn is clear of your arena bounds.");

        return justOutside(instance);
    }

    /**
     * A standing position one block past the arena's maximum X face.
     *
     * <p>Only reachable through misconfiguration, so it aims for "out of the
     * duel and on solid ground" rather than for anywhere pleasant.
     */
    private Location justOutside(ArenaInstance instance)
    {
        BlockBox bounds = instance.getBoundsBox();
        int x = bounds.maxX() + 1;
        int z = bounds.minZ() + bounds.sizeZ() / 2;

        return new Location(bounds.world(), x + 0.5, bounds.world().getHighestBlockYAt(x, z) + 1, z + 0.5);
    }

    private boolean insideAnyBounds(Location location)
    {
        for (ArenaInstance instance : arenaInstanceManager.getInstances())
            if (instance.hasBounds() && instance.contains(location))
                return true;

        return false;
    }

    private boolean isOccupantOf(Player player, ArenaInstance instance)
    {
        UUID uuid = player.getUniqueId();
        Match match = matchManager.getMatch(uuid);

        if (match != null && match.getArenaInstance().getId() == instance.getId())
            return true;

        SpectatorSession session = spectatorManager != null ? spectatorManager.getSession(uuid) : null;

        return session != null && session.getMatch() != null
                && session.getMatch().getState() != MatchState.ENDED
                && session.getMatch().getArenaInstance().getId() == instance.getId();
    }

    private void sendDeniedMessage(Player player)
    {
        long now = System.currentTimeMillis();
        Long last = lastDeniedMessageAt.get(player.getUniqueId());

        if (last != null && now - last < DENIED_MESSAGE_COOLDOWN_MS)
            return;

        lastDeniedMessageAt.put(player.getUniqueId(), now);
        messageManager.send(player, Message.CANNOT_ENTER_ARENA);
    }
}
