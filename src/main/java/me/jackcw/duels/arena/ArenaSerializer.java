package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.RepositorySerializer;
import me.jackcw.jcore.serialization.SerializerManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ArenaSerializer implements RepositorySerializer<Arena>
{
    private static final Set<String> FIELDS = Set.of(
            "name", "provisioningMode", "boundaryMode", "graceSeconds", "disallowedKits", "enabled");
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
        data.put("provisioningMode", arena.getProvisioningMode().name());
        data.put("boundaryMode", arena.getBoundaryMode().name());
        data.put("graceSeconds", arena.getGraceSeconds());
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

        if (map.get("provisioningMode") instanceof String provisioningMode)
        {
            try
            {
                arena.setProvisioningMode(ArenaProvisioningMode.valueOf(provisioningMode));
            }
            catch (IllegalArgumentException ignored)
            {
                // The safe fallback is STATIC: an invalid value must never
                // cause Duels to start creating arena copies unexpectedly.
            }
        }

        if (map.get("boundaryMode") instanceof String boundaryMode)
        {
            try
            {
                arena.setBoundaryMode(BoundaryMode.valueOf(boundaryMode));
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }

        if (map.get("graceSeconds") instanceof Number graceSeconds)
            arena.setGraceSeconds(graceSeconds.intValue());

        if (map.get("enabled") instanceof Boolean enabled)
            arena.setEnabled(enabled);

        if (map.get("disallowedKits") instanceof List<?> disallowedKits)
            for (Object kitId : disallowedKits)
                if (kitId instanceof Number number)
                    arena.disallowKit(number.intValue());

        return arena;
    }
}
