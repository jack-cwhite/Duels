package me.jackcw.duels.kit;

import me.jackcw.jcore.serialization.RepositorySerializer;
import me.jackcw.jcore.serialization.SerializerManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;

public class KitSerializer implements RepositorySerializer<Kit>
{
    private static final int CONTENTS_SIZE = 36;
    private static final int ARMOR_SIZE = 4;

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

        return kit;
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
