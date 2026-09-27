package me.jackcw.duels.kit;

import me.jackcw.jcore.serialization.RepositorySerializer;
import me.jackcw.jcore.serialization.SerializerManager;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class KitSerializer implements RepositorySerializer<Kit>
{
    private static final int CONTENTS_SIZE = 36;
    private static final int ARMOR_SIZE = 4;
    private static final Set<String> EFFECT_FIELDS = Set.of("type", "level", "ambient", "particles", "icon");

    private final SerializerManager serializerManager;

    public KitSerializer(SerializerManager serializerManager)
    {
        if (serializerManager == null)
            throw new IllegalArgumentException("Serializer manager cannot be null");

        this.serializerManager = serializerManager;
    }

    @Override
    public Object serialize(Kit kit)
    {
        Map<String, Object> data = new LinkedHashMap<>();

        data.put("name", kit.getName());

        if (kit.getContents() != null)
        {
            Map<String, Object> contents = serializeSlots(kit.getContents());

            if (!contents.isEmpty())
                data.put("contents", contents);
        }

        if (kit.getArmor() != null)
        {
            Map<String, Object> armor = serializeSlots(kit.getArmor());

            if (!armor.isEmpty())
                data.put("armor", armor);
        }

        if (kit.getOffHand() != null)
            data.put("offHand", serializerManager.serialize(kit.getOffHand()));

        if (kit.getIcon() != null)
            data.put("icon", serializerManager.serialize(kit.getIcon()));

        if (!kit.getEffects().isEmpty())
        {
            List<Map<String, Object>> effects = new ArrayList<>();
            for (KitEffect effect : kit.getEffects())
            {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("type", effect.typeKey().toString());
                entry.put("level", effect.level());
                entry.put("ambient", effect.ambient());
                entry.put("particles", effect.particles());
                entry.put("icon", effect.icon());
                effects.add(entry);
            }
            data.put("effects", effects);
        }

        return data;
    }

    @Override
    public Kit deserialize(int id, Object value)
    {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected a map when deserializing a kit");

        if (!(map.get("name") instanceof String name))
            throw new IllegalArgumentException("Expected a string for the name when deserializing a kit");

        Kit kit = new Kit(id, name);

        if (map.get("contents") instanceof Map<?, ?> contents)
            kit.setContents(deserializeSlots(contents, CONTENTS_SIZE));

        if (map.get("armor") instanceof Map<?, ?> armor)
            kit.setArmor(deserializeSlots(armor, ARMOR_SIZE));

        if (map.get("offHand") != null)
            kit.setOffHand(serializerManager.deserialize(map.get("offHand"), ItemStack.class));

        if (map.get("icon") != null)
            kit.setIcon(serializerManager.deserialize(map.get("icon"), ItemStack.class));

        if (map.containsKey("effects"))
        {
            if (!(map.get("effects") instanceof List<?> effects))
                throw new IllegalArgumentException("Kit " + id + " effects: expected a list");

            for (int index = 0; index < effects.size(); index++)
            {
                KitEffect effect = deserializeEffect(id, index, effects.get(index));
                if (kit.getEffect(effect.typeKey()) != null)
                    throw new IllegalArgumentException("Kit " + id + " effects[" + index + "].type: duplicate '" + effect.typeKey() + "'");
                kit.setEffect(effect);
            }
        }

        return kit;
    }

    private static KitEffect deserializeEffect(int kitId, int index, Object value)
    {
        String path = "Kit " + kitId + " effects[" + index + "]";
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException(path + ": expected a map");

        for (Object field : map.keySet())
            if (!(field instanceof String name) || !EFFECT_FIELDS.contains(name))
                throw new IllegalArgumentException(path + ": unknown field '" + field + "'");

        if (!(map.get("type") instanceof String text) || !text.contains(":"))
            throw new IllegalArgumentException(path + ".type: expected a namespaced registry key");

        NamespacedKey key = NamespacedKey.fromString(text);
        if (key == null)
            throw new IllegalArgumentException(path + ".type: invalid registry key '" + text + "'");

        Object rawLevel = map.get("level");
        if (!(rawLevel instanceof Integer level))
            throw new IllegalArgumentException(path + ".level: expected an integer from 1 to 255");

        boolean ambient = requireBoolean(map, "ambient", path);
        boolean particles = requireBoolean(map, "particles", path);
        boolean icon = requireBoolean(map, "icon", path);

        try
        {
            return new KitEffect(key, level, ambient, particles, icon);
        }
        catch (IllegalArgumentException exception)
        {
            String field = exception.getMessage().startsWith("Effect level") ? "level" : "type";
            throw new IllegalArgumentException(path + "." + field + ": " + exception.getMessage(), exception);
        }
    }

    private static boolean requireBoolean(Map<?, ?> map, String field, String path)
    {
        if (!(map.get(field) instanceof Boolean value))
            throw new IllegalArgumentException(path + "." + field + ": expected a boolean");
        return value;
    }

    private Map<String, Object> serializeSlots(ItemStack[] items)
    {
        Map<String, Object> slots = new LinkedHashMap<>();

        for (int slot = 0; slot < items.length; slot++)
        {
            ItemStack item = items[slot];

            if (item != null && item.getType() != Material.AIR)
                slots.put(String.valueOf(slot), serializerManager.serialize(item));
        }

        return slots;
    }

    private ItemStack[] deserializeSlots(Map<?, ?> map, int size)
    {
        ItemStack[] items = new ItemStack[size];

        for (Map.Entry<?, ?> entry : map.entrySet())
        {
            int slot;

            try
            {
                slot = Integer.parseInt(String.valueOf(entry.getKey()));
            }
            catch (NumberFormatException e)
            {
                continue;
            }

            if (slot < 0 || slot >= size)
                continue;

            items[slot] = serializerManager.deserialize(entry.getValue(), ItemStack.class);
        }

        return items;
    }
}
