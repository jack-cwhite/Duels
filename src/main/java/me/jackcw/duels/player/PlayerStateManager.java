package me.jackcw.duels.player;

import me.jackcw.duels.arena.ArenaEditSession;
import me.jackcw.jcore.serialization.PlayerState;
import me.jackcw.jcore.serialization.SerializerManager;
import me.jackcw.jcore.storage.YamlFile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

public final class PlayerStateManager
{
    private static final String ROOT = "states";
    private static final Logger LOGGER = Logger.getLogger(PlayerStateManager.class.getName());

    private final YamlFile file;
    private final SerializerManager serializers;
    private final Map<UUID, PlayerState> stateCache = new HashMap<>();

    public PlayerStateManager(YamlFile file, SerializerManager serializers)
    {
        this.file = file;
        this.serializers = serializers;

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

    public boolean restore(Player player)
    {
        PlayerState state = stateCache.get(player.getUniqueId());

        if (state == null)
            return false;

        if (state.getLocation() == null || !Bukkit.getWorlds().contains(state.getLocation().getWorld()))
        {
            LOGGER.warning("Discarding saved player state for '" + player.getUniqueId() + "': its world is no longer loaded");

            file.set(ROOT + "." + player.getUniqueId(), null);
            file.save();
            stateCache.remove(player.getUniqueId());

            return false;
        }

        // Restoration runs inside a join event, so anything thrown here would
        // leak into Bukkit's event handling rather than being reported against
        // this plugin. The snapshot is deliberately left in place on failure
        // (fail-forward) so a later attempt can still retry it, rather than
        // silently discarding a player's inventory because one item failed to
        // deserialize.
        try
        {
            state.apply(player);
        }
        catch (RuntimeException e)
        {
            LOGGER.warning("Could not restore saved player state for '" + player.getUniqueId() + "', leaving it saved for a later attempt: " + e.getMessage());
            return false;
        }

        file.set(ROOT + "." + player.getUniqueId(), null);
        file.save();
        stateCache.remove(player.getUniqueId());

        return true;
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
