package me.jackcw.duels.kit;

import me.jackcw.jcore.item.ItemStackParser;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Map;

public final class KitIcons
{
    private static final Material DEFAULT_MATERIAL = Material.DIAMOND_SWORD;

    private KitIcons()
    {
    }

    public static ItemStack build(Kit kit, ConfigurationSection entryTemplate, Map<String, Object> placeholders)
    {
        ItemStack icon = kit.getIcon();
        Material material = icon != null ? icon.getType() : DEFAULT_MATERIAL;

        Map<String, Object> merged = new HashMap<>(placeholders);
        merged.put("material", material.name());

        ItemStack item = ItemStackParser.parseSafely(entryTemplate, merged);

        if (icon != null && !icon.getEnchantments().isEmpty())
        {
            ItemMeta meta = item.getItemMeta();
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }

        return item;
    }
}
