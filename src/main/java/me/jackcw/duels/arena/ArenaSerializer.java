package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.RepositorySerializer;
import me.jackcw.jcore.serialization.SerializerManager;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArenaSerializer implements RepositorySerializer<Arena>
{
    private static final Set<String> FIELDS = Set.of("name", "spawn1", "spawn2", "disallowedKits", "enabled");
    private final SerializerManager serializerManager;

    public ArenaSerializer(SerializerManager serializerManager)
    {
        if (serializerManager == null)
            throw new IllegalArgumentException("Serializer manager cannot be null");

        this.serializerManager = serializerManager;
    }

    @Override
    public Object serialize(Arena arena)
    {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("name", arena.getName());
        data.put("spawn1", serializerManager.serialize(arena.getSpawn1()));
        data.put("spawn2", serializerManager.serialize(arena.getSpawn2()));
        data.put("disallowedKits", new ArrayList<>(arena.getDisallowedKitIds()));
        data.put("enabled", arena.isEnabled());

        return data;
    }

    @Override
    public Arena deserialize(int id, Object value)
    {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected a map when deserializing an arena");

        for (Object key : map.keySet())
            if (!(key instanceof String field) || !FIELDS.contains(field))
                throw new IllegalArgumentException("Unknown arena field '" + key + "'");

        if (!(map.get("name") instanceof String name) || name.isBlank())
            throw new IllegalArgumentException("Expected a non-empty arena name");

        Arena arena = new Arena(id, name);

        arena.setSpawn1(serializerManager.deserialize(map.get("spawn1"), Location.class));
        arena.setSpawn2(serializerManager.deserialize(map.get("spawn2"), Location.class));

        if (map.get("enabled") instanceof Boolean enabled)
            arena.setEnabled(enabled);

        if (map.get("disallowedKits") instanceof List<?> disallowedKits)
            for (Object kitId : disallowedKits)
                if (kitId instanceof Number number)
                    arena.disallowKit(number.intValue());

        return arena;
    }
}
