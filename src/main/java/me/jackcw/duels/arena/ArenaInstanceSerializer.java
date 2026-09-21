package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.RepositorySerializer;
import me.jackcw.jcore.serialization.SerializerManager;
import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ArenaInstanceSerializer implements RepositorySerializer<ArenaInstance>
{
    private static final Set<String> FIELDS = Set.of("arenaId", "origin", "dynamic", "spawn1", "spawn2", "boundsCorner1", "boundsCorner2");
    private static final Set<String> DYNAMIC_FIELDS = Set.of("slot", "templateRevision", "size", "state");
    private static final Set<String> SIZE_FIELDS = Set.of("x", "y", "z");
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
        data.put("origin", instance.getOrigin().name());
        if (instance.isProvisioned())
            data.put("dynamic", serializeDynamic(instance));
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

        if (map.get("origin") instanceof String origin)
        {
            ArenaInstanceOrigin parsed;
            try { parsed = ArenaInstanceOrigin.valueOf(origin); }
            catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Unknown arena instance origin '" + origin + "'", exception); }
            if (parsed == ArenaInstanceOrigin.PROVISIONED)
                deserializeProvisioned(instance, map.get("dynamic"));
            else if (map.containsKey("dynamic"))
                throw new IllegalArgumentException("Only generated copies may have dynamic metadata");
            else if (parsed == ArenaInstanceOrigin.SOURCE)
                instance.markSource();
        }
        else if (map.containsKey("dynamic"))
            throw new IllegalArgumentException("Only generated copies may have dynamic metadata");

        instance.setSpawn1(serializerManager.deserialize(map.get("spawn1"), Location.class));
        instance.setSpawn2(serializerManager.deserialize(map.get("spawn2"), Location.class));
        instance.setBoundsCorner1(serializerManager.deserialize(map.get("boundsCorner1"), Location.class));
        instance.setBoundsCorner2(serializerManager.deserialize(map.get("boundsCorner2"), Location.class));

        return instance;
    }

    private static Map<String, Object> serializeDynamic(ArenaInstance instance)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slot", instance.getDynamicSlotIndex());
        data.put("templateRevision", instance.getTemplateRevision());
        Map<String, Object> size = new LinkedHashMap<>();
        size.put("x", instance.getStructureSize().x());
        size.put("y", instance.getStructureSize().y());
        size.put("z", instance.getStructureSize().z());
        data.put("size", size);
        data.put("state", instance.getDynamicState().name());
        return data;
    }

    private static void deserializeProvisioned(ArenaInstance instance, Object value)
    {
        if (!(value instanceof Map<?, ?> dynamic))
            throw new IllegalArgumentException("Provisioned arena instance needs dynamic metadata");

        for (Object key : dynamic.keySet())
            if (!(key instanceof String field) || !DYNAMIC_FIELDS.contains(field))
                throw new IllegalArgumentException("Unknown dynamic arena instance field '" + key + "'");

        if (!(dynamic.get("slot") instanceof Number slot) || !(dynamic.get("templateRevision") instanceof Number revision)
                || !(dynamic.get("state") instanceof String rawState) || !(dynamic.get("size") instanceof Map<?, ?> size))
            throw new IllegalArgumentException("Provisioned arena instance has incomplete dynamic metadata");

        for (Object key : size.keySet())
            if (!(key instanceof String field) || !SIZE_FIELDS.contains(field))
                throw new IllegalArgumentException("Unknown dynamic arena size field '" + key + "'");

        if (!(size.get("x") instanceof Number x) || !(size.get("y") instanceof Number y) || !(size.get("z") instanceof Number z))
            throw new IllegalArgumentException("Provisioned arena instance has incomplete structure size");

        try
        {
            instance.configureProvisioned(slot.intValue(), revision.intValue(),
                    new ArenaStructureSize(x.intValue(), y.intValue(), z.intValue()), DynamicArenaState.valueOf(rawState));
        }
        catch (IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("Provisioned arena instance has invalid dynamic metadata", exception);
        }
    }
}
