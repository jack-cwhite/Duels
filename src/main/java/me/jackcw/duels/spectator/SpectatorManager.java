package me.jackcw.duels.spectator;

import me.jackcw.duels.Duels;
import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaManager;
import me.jackcw.duels.match.Match;
import me.jackcw.duels.match.MatchManager;
import me.jackcw.duels.match.MatchState;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import me.jackcw.jcore.storage.YamlFile;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Owns who is spectating which match, and is the only thing allowed to change
 * that.
 *
 * <p><strong>Spectators never appear in {@code MatchManager}'s match map.</strong>
 * Membership of that map is the definition of "combatant" - every listener asks
 * it - so a spectator in it would be treated as a fighter. Most damagingly,
 * {@code MatchListener.onPlayerQuitWhileInMatch} would award the match to a
 * combatant because a <em>spectator</em> disconnected. "Am I fighting?" and
 * "am I watching?" are deliberately two separate questions.
 *
 * <p>Sessions are persisted because of the crash case. On a crash neither
 * {@code PlayerQuitEvent} nor {@code onDisable} runs, so nothing puts the player
 * back - they would return flying through blocks in an arena with no way to fix
 * it themselves. The persisted row lets {@link #restoreOnJoin(Player)} finish
 * the job on their next login, which is the same reasoning that makes
 * {@code PlayerStateManager} a file rather than a map.
 */
public final class SpectatorManager
{
    private static final String ROOT = "spectators";
    private static final Logger LOGGER = Logger.getLogger(SpectatorManager.class.getName());

    private final Duels plugin;
    private final YamlFile file;
    private final MessageManager messageManager;
    private final MatchManager matchManager;
    private final ArenaManager arenaManager;

    // Live sessions only. Rows on disk for players who are not currently
    // spectating are unfinished restorations, and are read straight from the
    // file when that player joins rather than being held here - so this map
    // never contains anybody who is not, right now, watching a match.
    private final Map<UUID, SpectatorSession> sessions = new HashMap<>();

    public SpectatorManager(Duels plugin, YamlFile file)
    {
        this.plugin = plugin;
        this.file = file;
        this.messageManager = plugin.core().messages();
        this.matchManager = plugin.getMatchManager();
        this.arenaManager = plugin.getArenaManager();
    }

    public SpectateResult start(Player spectator, Match match)
    {
        if (match == null || match.getState() == MatchState.ENDED)
            return SpectateResult.MATCH_UNAVAILABLE;

        UUID uuid = spectator.getUniqueId();

        if (matchManager.getMatch(uuid) != null)
            return SpectateResult.ALREADY_IN_MATCH;

        SpectatorSession existing = sessions.get(uuid);

        if (existing != null && existing.getMatch() == match)
            return SpectateResult.ALREADY_SPECTATING;

        Arena arena = arenaManager.getArena(match.getArenaInstance().getArenaId());

        if (arena == null || !match.getArenaInstance().hasBounds())
            return SpectateResult.NO_BOUNDS;

        // Switching from another match: undo the old session first, so the
        // location captured below is where they were before they started
        // spectating anything - not the middle of the arena they just left.
        if (existing != null)
            stop(uuid);

        SpectatorSession session = SpectatorSession.capture(spectator, match, arena.getId());

        sessions.put(uuid, session);
        persist(session);

        spectator.setGameMode(GameMode.SPECTATOR);

        if (!spectator.teleport(match.getArenaInstance().getSpawn1()))
        {
            // Rolled back rather than left half-applied. Otherwise they sit in
            // SPECTATOR outside the arena while a session claims they are
            // watching a duel they cannot see.
            stop(uuid);
            return SpectateResult.TELEPORT_FAILED;
        }

        return SpectateResult.SUCCESS;
    }

    /**
     * Puts a spectator back as they were, now.
     *
     * <p>The persisted row is cleared only once the teleport home has actually
     * succeeded, so a refused teleport is retried on their next join instead of
     * being silently forgotten. The in-memory session is dropped either way:
     * their gamemode is already restored, so they are no longer spectating in
     * any meaningful sense and nothing should keep constraining them to the
     * arena.
     */
    public boolean stop(UUID uuid)
    {
        SpectatorSession session = sessions.remove(uuid);

        if (session == null)
            return false;

        Player player = plugin.getServer().getPlayer(uuid);

        if (player == null || !player.isOnline())
        {
            // Nothing to restore onto. The row stays for the join handler.
            return false;
        }

        if (!restore(player, session))
            return false;

        clearPersisted(uuid);

        // Mirrors MatchManager.forgetParticipant for the spectator side of the
        // same problem - a spectator whose session ends normally (leaving via
        // /duel leave, or being ejected because the match they watched ended)
        // never fires PlayerQuitEvent, so without this BoundaryEnforcer keeps
        // tracking them under a match they are no longer attached to for as
        // long as they stay online.
        plugin.getBoundaryEnforcer().forget(uuid);

        return true;
    }

    /**
     * Drops the live session but leaves the persisted row, so the player is put
     * back by {@link #restoreOnJoin(Player)} instead of right now.
     *
     * <p>Used when restoring immediately would be unreliable or pointless: a
     * disconnect (teleporting a player who is already leaving) and server
     * shutdown (teleports late in shutdown are not dependable). This is the same
     * trade {@code MatchManager.shutdown(preservePlayerStates)} already makes -
     * preserve and restore later rather than restore now and hope.
     */
    public boolean detach(UUID uuid)
    {
        return sessions.remove(uuid) != null;
    }

    public void stopAll(Match match, boolean preserveForRestoreOnJoin)
    {
        for (UUID uuid : spectatorsOf(match))
            end(uuid, preserveForRestoreOnJoin, Message.SPECTATE_MATCH_ENDED);
    }

    public void shutdown(boolean preserveForRestoreOnJoin)
    {
        for (UUID uuid : new ArrayList<>(sessions.keySet()))
            end(uuid, preserveForRestoreOnJoin, null);
    }

    private void end(UUID uuid, boolean preserveForRestoreOnJoin, Message notice)
    {
        if (preserveForRestoreOnJoin)
        {
            detach(uuid);
            return;
        }

        if (!stop(uuid) || notice == null)
            return;

        Player player = plugin.getServer().getPlayer(uuid);

        if (player != null)
            messageManager.send(player, notice);
    }

    /**
     * Finishes a session that outlived the server that created it.
     *
     * <p>This is the crash path, and also the ordinary disconnect path - both
     * leave a row behind on purpose.
     */
    public void restoreOnJoin(Player player)
    {
        UUID uuid = player.getUniqueId();

        if (!file.contains(ROOT + "." + uuid))
            return;

        SpectatorSession session = read(uuid);

        if (session == null)
        {
            LOGGER.warning("Discarding unreadable spectator session for '" + uuid + "'");
            clearPersisted(uuid);
            return;
        }

        if (!restore(player, session))
        {
            LOGGER.warning("Could not fully restore spectator session for '" + uuid + "', leaving it saved for a later attempt");
            return;
        }

        clearPersisted(uuid);
        messageManager.send(player, Message.SPECTATE_RESTORED);
    }

    public SpectatorSession getSession(UUID uuid)
    {
        return sessions.get(uuid);
    }

    public boolean isSpectating(UUID uuid)
    {
        return sessions.containsKey(uuid);
    }

    public List<UUID> spectatorsOf(Match match)
    {
        if (match == null)
            return List.of();

        List<UUID> watching = new ArrayList<>();

        for (Map.Entry<UUID, SpectatorSession> entry : sessions.entrySet())
            if (entry.getValue().getMatch() == match)
                watching.add(entry.getKey());

        return watching;
    }

    public Map<UUID, SpectatorSession> getSessions()
    {
        return Collections.unmodifiableMap(sessions);
    }

    /**
     * @return whether the player was returned to their saved location. The
     *         gamemode is restored regardless - a wrong position is an
     *         inconvenience, a stuck gamemode is a support ticket.
     */
    private boolean restore(Player player, SpectatorSession session)
    {
        player.setGameMode(session.getPreviousGameMode());
        player.setAllowFlight(session.wasAllowedFlight());

        // setFlying throws if flight is not allowed, which it will not be for a
        // player restored to SURVIVAL.
        if (session.wasAllowedFlight())
            player.setFlying(session.wasFlying());

        Location target = session.getReturnLocation();

        if (target == null || target.getWorld() == null || !Bukkit.getWorlds().contains(target.getWorld()))
        {
            LOGGER.warning("Spectator '" + player.getUniqueId() + "' cannot be returned to their saved location because its world is no longer loaded; sending them to the main world spawn");

            List<World> worlds = Bukkit.getWorlds();

            if (!worlds.isEmpty())
                player.teleport(worlds.getFirst().getSpawnLocation());

            // Treated as done: the location is unrecoverable, so retrying on a
            // later join would never succeed and would strand the row forever.
            return true;
        }

        return player.teleport(target);
    }

    private void persist(SpectatorSession session)
    {
        String path = ROOT + "." + session.getSpectatorId();

        file.set(path + ".arena-id", session.getArenaId());
        file.set(path + ".return-location", session.getReturnLocation());
        file.set(path + ".previous-gamemode", session.getPreviousGameMode().name());
        file.set(path + ".previous-allow-flight", session.wasAllowedFlight());
        file.set(path + ".previous-flying", session.wasFlying());
        file.save();
    }

    private SpectatorSession read(UUID uuid)
    {
        ConfigurationSection section = file.getConfig().getConfigurationSection(ROOT + "." + uuid);

        if (section == null)
            return null;

        try
        {
            return new SpectatorSession(
                    uuid,
                    section.getInt("arena-id"),
                    section.getLocation("return-location"),
                    GameMode.valueOf(section.getString("previous-gamemode", GameMode.SURVIVAL.name())),
                    section.getBoolean("previous-allow-flight"),
                    section.getBoolean("previous-flying"),
                    null
            );
        }
        catch (RuntimeException e)
        {
            LOGGER.warning("Could not read spectator session for '" + uuid + "': " + e.getMessage());
            return null;
        }
    }

    private void clearPersisted(UUID uuid)
    {
        file.set(ROOT + "." + uuid, null);
        file.save();
    }
}
