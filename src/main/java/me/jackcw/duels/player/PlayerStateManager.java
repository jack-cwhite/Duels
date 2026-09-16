package me.jackcw.duels.player;

import me.jackcw.jcore.serialization.PlayerState;
import me.jackcw.jcore.serialization.SerializerManager;
import me.jackcw.jcore.storage.YamlFile;
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
    private final Map<UUID, PlayerState> cache = new HashMap<>();

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
                    cache.put(uuid, state);
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

        cache.put(player.getUniqueId(), state);

        file.set(ROOT + "." + player.getUniqueId(), serializers.serialize(state));
        file.save();
    }

    public boolean restore(Player player)
    {
        PlayerState state = cache.get(player.getUniqueId());

        if (state == null)
            return false;

        state.apply(player);

        file.set(ROOT + "." + player.getUniqueId(), null);
        file.save();
        cache.remove(player.getUniqueId());

        return true;
    }

    public boolean has(Player player)
    {
        return cache.containsKey(player.getUniqueId());
    }

    public void clear(Player player)
    {
        cache.remove(player.getUniqueId());

        file.set(ROOT + "." + player.getUniqueId(), null);
        file.save();
    }

    public PlayerState get(Player player)
    {
        return cache.get(player.getUniqueId());
    }
}
