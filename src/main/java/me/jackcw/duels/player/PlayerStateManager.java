package me.jackcw.duels.player;

import me.jackcw.duels.DuelsSettings;
import me.jackcw.duels.arena.ArenaEditSession;
import me.jackcw.jcore.serialization.PlayerState;
import me.jackcw.jcore.serialization.SerializerManager;
import me.jackcw.jcore.storage.YamlFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PlayerStateManager
{
    private static final String ROOT = "states";
    private static final Logger LOGGER = Logger.getLogger(PlayerStateManager.class.getName());

    private final YamlFile file;
    private final SerializerManager serializers;
    private final DuelsSettings settings;
    private final Map<UUID, PlayerState> stateCache = new HashMap<>();

    public PlayerStateManager(YamlFile file, SerializerManager serializers, DuelsSettings settings)
    {
        this.file = file;
        this.serializers = serializers;
        this.settings = settings;

        load();
    }

    private void load()
    {
        ConfigurationSection section = file.getConfig().getConfigurationSection(ROOT);

        if (section == null)
            return;

        for (String key : section.getKeys(false))
        {
            try
            {
                UUID uuid = UUID.fromString(key);
                PlayerState state = file.get(ROOT + "." + key, PlayerState.class);

                if (state != null)
                    stateCache.put(uuid, state);
            }
            catch (RuntimeException e)
            {
                LOGGER.warning("Skipping unreadable saved player state '" + key + "': " + e.getMessage());
            }
        }
    }

    public void save(Player player)
    {
        PlayerState state = PlayerState.capture(player);

        stateCache.put(player.getUniqueId(), state);

        file.set(ROOT + "." + player.getUniqueId(), serializers.serialize(state));
        file.save();
    }

    /**
     * Puts {@code player} back the way they were before Duels took them into a
     * duel, an arena edit session, or spectating.
     *
     * <p>A snapshot whose captured world has since gone - deleted, renamed, or
     * simply not loaded on this start - is still applied, at
     * {@link DuelsSettings#fallbackSpawn()} rather than the captured position.
     * Throwing it away instead would cost the player their inventory in order
     * to save them a teleport, which is the wrong way round, and admins do
     * remove worlds: the world a duel was accepted in is not guaranteed to
     * outlive the duel.
     */
    public boolean restore(Player player)
    {
        PlayerState state = stateCache.get(player.getUniqueId());

        if (state == null)
            return false;

        Location destination = destinationFor(state);

        if (!capturedLocationIsUsable(state))
            LOGGER.warning("The world '" + player.getName() + "' was in before Duels moved them is no longer loaded,"
                    + " so they were returned to the spawn of '" + destination.getWorld().getName()
                    + "' instead; everything else they had has been restored.");

        // Restoration runs inside a join event, so anything thrown here would
        // leak into Bukkit's event handling rather than being reported against
        // this plugin. The snapshot is deliberately left in place on failure
        // (fail-forward) so a later attempt can still retry it, rather than
        // silently discarding a player's inventory because one item failed to
        // deserialize.
        try
        {
            state.apply(player, destination);
        }
        catch (RuntimeException e)
        {
            LOGGER.log(Level.WARNING, "Could not restore saved player state for '" + player.getUniqueId() + "', leaving it saved for a later attempt", e);
            return false;
        }

        file.set(ROOT + "." + player.getUniqueId(), null);
        file.save();
        stateCache.remove(player.getUniqueId());

        return true;
    }

    /**
     * Where {@link #restore} would put {@code player}, or {@code null} if they
     * have no saved state.
     *
     * <p>Exposed for two callers that need the answer without performing the
     * restore: {@code MatchManager} choosing a respawn location for a duellist
     * who died, one tick before the restore itself, so it cannot name a world
     * that {@code restore} is about to reject; and the diagnostics command,
     * which reports where a saved state would land. Deliberately silent - the
     * warning belongs to the restore that actually relocates the player, not to
     * every question about one.
     */
    public Location restoreDestination(Player player)
    {
        PlayerState state = stateCache.get(player.getUniqueId());

        return state == null ? null : destinationFor(state);
    }

    /**
     * Whether {@code player}'s saved state still names a world Duels can put
     * them back in, or {@code false} if they have no saved state at all.
     *
     * <p>Exposed so the diagnostics command can say why a destination differs
     * from the captured position rather than leaving an admin to compare
     * coordinates and guess.
     */
    public boolean canRestoreToCapturedLocation(Player player)
    {
        PlayerState state = stateCache.get(player.getUniqueId());

        return state != null && capturedLocationIsUsable(state);
    }

    private Location destinationFor(PlayerState state)
    {
        return capturedLocationIsUsable(state) ? state.getLocation() : settings.fallbackSpawn();
    }

    /**
     * A captured location survives a restart as world name plus coordinates, so
     * the world it names may have been deleted or renamed, or may simply not be
     * loaded yet. Every other field in the snapshot is still good in that case,
     * which is why this is a question about the destination only.
     */
    private boolean capturedLocationIsUsable(PlayerState state)
    {
        Location captured = state.getLocation();

        return captured != null && captured.getWorld() != null && Bukkit.getWorlds().contains(captured.getWorld());
    }

    /**
     * How many players currently have a snapshot waiting to be applied.
     *
     * <p>Exposed for diagnostics: a snapshot outliving the match it was taken
     * for is a leak that shows up as a player keeping a kit or being unable to
     * get their own inventory back, and there is otherwise no way to see one
     * without reading playerstates.yml off disk.
     */
    public int getSavedStateCount()
    {
        return stateCache.size();
    }

    public boolean has(Player player)
    {
        return stateCache.containsKey(player.getUniqueId());
    }

    public void clear(Player player)
    {
        stateCache.remove(player.getUniqueId());

        file.set(ROOT + "." + player.getUniqueId(), null);
        file.save();
    }

    public PlayerState get(Player player)
    {
        return stateCache.get(player.getUniqueId());
    }
}
