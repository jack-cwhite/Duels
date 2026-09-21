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
            "name", "provisioningMode", "template", "boundaryMode", "graceSeconds", "disallowedKits", "enabled");
    private static final Set<String> TEMPLATE_FIELDS = Set.of(
            "revision", "provider", "file", "size", "spawn1", "spawn2", "boundsCorner1", "boundsCorner2");
    private static final Set<String> POSITION_FIELDS = Set.of("x", "y", "z");
    private static final Set<String> LOCATION_FIELDS = Set.of("x", "y", "z", "yaw", "pitch");
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
        if (arena.getTemplateDefinition() != null)
            data.put("template", serializeTemplate(arena.getTemplateDefinition()));
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

        if (map.containsKey("template"))
            arena.setTemplateDefinition(deserializeTemplate(map.get("template")));

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

    private static Map<String, Object> serializeTemplate(ArenaTemplateDefinition template)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("revision", template.revision());
        data.put("provider", template.providerId());
        data.put("file", template.fileName());
        data.put("size", serializePosition(template.size().x(), template.size().y(), template.size().z()));
        data.put("spawn1", serializeLocation(template.spawn1()));
        data.put("spawn2", serializeLocation(template.spawn2()));
        data.put("boundsCorner1", serializePosition(template.boundsCorner1().x(), template.boundsCorner1().y(), template.boundsCorner1().z()));
        data.put("boundsCorner2", serializePosition(template.boundsCorner2().x(), template.boundsCorner2().y(), template.boundsCorner2().z()));
        return data;
    }

    private static Map<String, Object> serializePosition(int x, int y, int z)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("x", x);
        data.put("y", y);
        data.put("z", z);
        return data;
    }

    private static Map<String, Object> serializeLocation(RelativeArenaLocation location)
    {
        Map<String, Object> data = serializePosition(location.x(), location.y(), location.z());
        data.put("yaw", location.yaw());
        data.put("pitch", location.pitch());
        return data;
    }

    private static ArenaTemplateDefinition deserializeTemplate(Object value)
    {
        Map<?, ?> map = requireMap(value, "template", TEMPLATE_FIELDS);
        int revision = requireInt(map, "revision");
        String provider = requireString(map, "provider");
        String file = requireString(map, "file");
        ArenaStructureSize size = new ArenaStructureSize(
                requireInt(requireMap(map.get("size"), "template size", POSITION_FIELDS), "x"),
                requireInt(requireMap(map.get("size"), "template size", POSITION_FIELDS), "y"),
                requireInt(requireMap(map.get("size"), "template size", POSITION_FIELDS), "z")
        );
        return new ArenaTemplateDefinition(
                revision, provider, file, size,
                deserializeLocation(map.get("spawn1"), "template spawn1"),
                deserializeLocation(map.get("spawn2"), "template spawn2"),
                deserializePosition(map.get("boundsCorner1"), "template boundsCorner1"),
                deserializePosition(map.get("boundsCorner2"), "template boundsCorner2")
        );
    }

    private static RelativeBlockPosition deserializePosition(Object value, String name)
    {
        Map<?, ?> map = requireMap(value, name, POSITION_FIELDS);
        return new RelativeBlockPosition(requireInt(map, "x"), requireInt(map, "y"), requireInt(map, "z"));
    }

    private static RelativeArenaLocation deserializeLocation(Object value, String name)
    {
        Map<?, ?> map = requireMap(value, name, LOCATION_FIELDS);
        return new RelativeArenaLocation(
                requireInt(map, "x"), requireInt(map, "y"), requireInt(map, "z"),
                requireFloat(map, "yaw"), requireFloat(map, "pitch")
        );
    }

    private static Map<?, ?> requireMap(Object value, String name, Set<String> allowedFields)
    {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected " + name + " to be a map");

        for (Object key : map.keySet())
            if (!(key instanceof String field) || !allowedFields.contains(field))
                throw new IllegalArgumentException("Unknown " + name + " field '" + key + "'");

        return map;
    }

    private static String requireString(Map<?, ?> map, String field)
    {
        if (!(map.get(field) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("Expected a non-empty " + field);
        return value;
    }

    private static int requireInt(Map<?, ?> map, String field)
    {
        if (!(map.get(field) instanceof Number value))
            throw new IllegalArgumentException("Expected a numeric " + field);
        return value.intValue();
    }

    private static float requireFloat(Map<?, ?> map, String field)
    {
        if (!(map.get(field) instanceof Number value))
            throw new IllegalArgumentException("Expected a numeric " + field);
        return value.floatValue();
    }
}
