package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ArenaEditManager
{
    private final NamespacedKey toolKey;
    private final Map<UUID, ArenaEditSession> sessions = new HashMap<>();

    public ArenaEditManager(Duels plugin)
    {
        this.toolKey = new NamespacedKey(plugin, "arena-edit-tool");
    }

    public void start(Player player, Arena arena)
    {
        if (isEditing(player))
            end(player);

        ArenaEditSession session = new ArenaEditSession(player, arena);
        sessions.put(player.getUniqueId(), session);

        ArenaEditTool[] tools = ArenaEditTool.values();

        for (int i = 0; i < tools.length; i++)
            player.getInventory().setItem(i, createToolItem(tools[i]));
    }

    public void end(Player player)
    {
        ArenaEditSession session = sessions.remove(player.getUniqueId());

        if (session == null)
            return;

        ItemStack[] savedHotbar = session.getSavedHotbar();

        for (int i = 0; i < savedHotbar.length; i++)
            player.getInventory().setItem(i, savedHotbar[i]);
    }

    public ArenaEditTool getTool(ItemStack item)
    {
        if (item == null || !item.hasItemMeta())
            return null;

        String value = item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);

        if (value == null)
            return null;

        try
        {
            return ArenaEditTool.valueOf(value);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    private ItemStack createToolItem(ArenaEditTool tool)
    {
        ItemStack item = new ItemStack(tool.getMaterial());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tool.getDisplayName()));
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.name());

        item.setItemMeta(meta);
        return item;
    }

    public ArenaEditSession getSession(UUID uuid)
    {
        return sessions.get(uuid);
    }

    public ArenaEditSession getSession(Player player)
    {
        return getSession(player.getUniqueId());
    }

    public boolean isEditing(UUID uuid)
    {
        return sessions.containsKey(uuid);
    }

    public boolean isEditing(Player player)
    {
        return isEditing(player.getUniqueId());
    }
}
