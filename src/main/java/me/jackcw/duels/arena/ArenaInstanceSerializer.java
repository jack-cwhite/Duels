package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.RepositorySerializer;
import me.jackcw.jcore.serialization.SerializerManager;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ArenaInstanceSerializer implements RepositorySerializer<ArenaInstance>
{
    private static final Set<String> FIELDS = Set.of("arenaId", "spawn1", "spawn2", "boundsCorner1", "boundsCorner2");
    private final SerializerManager serializerManager;

    public ArenaInstanceSerializer(SerializerManager serializerManager)
    {
        if (serializerManager == null)
            throw new IllegalArgumentException("Serializer manager cannot be null");

        this.serializerManager = serializerManager;
    }

    @Override
    public Object serialize(ArenaInstance instance)
    {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("arenaId", instance.getArenaId());
        data.put("spawn1", serializerManager.serialize(instance.getSpawn1()));
        data.put("spawn2", serializerManager.serialize(instance.getSpawn2()));
        data.put("boundsCorner1", serializerManager.serialize(instance.getBoundsCorner1()));
        data.put("boundsCorner2", serializerManager.serialize(instance.getBoundsCorner2()));

        return data;
    }

    @Override
    public ArenaInstance deserialize(int id, Object value)
    {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected a map when deserializing an arena instance");

        for (Object key : map.keySet())
            if (!(key instanceof String field) || !FIELDS.contains(field))
                throw new IllegalArgumentException("Unknown arena instance field '" + key + "'");

        if (!(map.get("arenaId") instanceof Number arenaId))
            throw new IllegalArgumentException("Expected an arena instance to reference an arena id");

        ArenaInstance instance = new ArenaInstance(id, arenaId.intValue());

        instance.setSpawn1(serializerManager.deserialize(map.get("spawn1"), Location.class));
        instance.setSpawn2(serializerManager.deserialize(map.get("spawn2"), Location.class));
        instance.setBoundsCorner1(serializerManager.deserialize(map.get("boundsCorner1"), Location.class));
        instance.setBoundsCorner2(serializerManager.deserialize(map.get("boundsCorner2"), Location.class));

        return instance;
    }
}
