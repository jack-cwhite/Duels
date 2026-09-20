package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.jcore.item.ItemStackParser;
import me.jackcw.jcore.menu.SlotResolver;
import me.jackcw.jcore.storage.YamlFile;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class ArenaEditManager
{
    private static final int HOTBAR_SIZE = 9;

    private final Duels plugin;

    private final NamespacedKey toolKey;
    private final ConfigurationSection toolsSection;
    private final Map<ArenaEditTool, Integer> toolSlots;

    public Map<UUID, ArenaEditSession> getSessions()
    {
        return sessions;
    }

    private final Map<UUID, ArenaEditSession> sessions = new HashMap<>();

    public ArenaEditManager(Duels plugin)
    {
        this.plugin = plugin;
        this.toolKey = new NamespacedKey(plugin, "arena-edit-tool");

        YamlFile file = plugin.core().files().yaml("arena-edit.yml", true);
        file.updateDefaults();

        this.toolsSection = file.getConfig().getConfigurationSection("tools");
        this.toolSlots = resolveToolSlots(toolsSection, plugin.getLogger());
    }

    public void start(Player player, Arena arena)
    {
        if (isEditing(player))
            end(player);

        ArenaEditSession session = new ArenaEditSession(player, arena);
        sessions.put(player.getUniqueId(), session);

        giveTools(player);
    }

    public void giveTools(Player player)
    {
        PlayerInventory inventory = player.getInventory();

        for (int slot = 0; slot < HOTBAR_SIZE; slot++)
            inventory.setItem(slot, null);

        inventory.setItemInOffHand(null);

        for (Map.Entry<ArenaEditTool, Integer> entry : toolSlots.entrySet())
            inventory.setItem(entry.getValue(), createToolItem(entry.getKey()));
    }

    public boolean isTool(ItemStack item)
    {
        return getTool(item) != null;
    }

    public void end(UUID uuid)
    {
        Player player = getPlayer(uuid);

        if (player != null)
            end(player);
    }

    public void end(Player player)
    {
        ArenaEditSession session = sessions.remove(player.getUniqueId());

        if (session == null)
            return;

        ItemStack[] savedHotbar = session.getSavedHotbar();
        ItemStack savedOffhand = session.getSavedOffhand();

        for (int i = 0; i < savedHotbar.length; i++)
            player.getInventory().setItem(i, savedHotbar[i]);

        if (savedOffhand.getType() != Material.AIR)
            player.getInventory().setItemInOffHand(savedOffhand);

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

    private static Map<ArenaEditTool, Integer> resolveToolSlots(ConfigurationSection section, Logger logger)
    {
        Map<String, Integer> configured = SlotResolver.resolve(1, section, logger::warning);
        Map<ArenaEditTool, Integer> slots = new EnumMap<>(ArenaEditTool.class);
        Set<Integer> used = new HashSet<>();

        for (Map.Entry<String, Integer> entry : configured.entrySet())
        {
            ArenaEditTool tool = ArenaEditTool.byConfigKey(entry.getKey());

            if (tool == null)
            {
                logger.warning("Ignoring unknown arena edit tool '" + entry.getKey() + "' in arena-edit.yml");
                continue;
            }

            slots.put(tool, entry.getValue());
            used.add(entry.getValue());
        }

        for (ArenaEditTool tool : ArenaEditTool.values())
        {
            if (slots.containsKey(tool))
                continue;

            int slot = nextFreeSlot(used);

            if (slot == -1)
            {
                logger.warning("Arena edit tool '" + tool.getConfigKey() + "' could not be placed - the hotbar is full");
                continue;
            }

            logger.warning("Arena edit tool '" + tool.getConfigKey() + "' is not configured in arena-edit.yml; placing it at slot " + slot);
            slots.put(tool, slot);
            used.add(slot);
        }

        return slots;
    }

    private static int nextFreeSlot(Set<Integer> used)
    {
        for (int slot = 0; slot < HOTBAR_SIZE; slot++)
            if (!used.contains(slot))
                return slot;

        return -1;
    }

    private ItemStack createToolItem(ArenaEditTool tool)
    {
        ConfigurationSection section = toolsSection != null
                ? toolsSection.getConfigurationSection(tool.getConfigKey())
                : null;

        ItemStack item = ItemStackParser.parseSafely(section, defaultToolItem(tool));

        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, tool.name());
        item.setItemMeta(meta);

        return item;
    }

    private ItemStack defaultToolItem(ArenaEditTool tool)
    {
        ItemStack item = new ItemStack(tool.getMaterial());
        ItemMeta meta = item.getItemMeta();

        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(tool.getDisplayName()));

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

    public Player getPlayer(UUID uuid)
    {
        return plugin.getServer().getPlayer(uuid);
    }
}
