package me.jackcw.duels.menu.user;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;

final class StatsMenuItems
{
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm")
            .withZone(ZoneId.systemDefault());

    private StatsMenuItems() {}

    static ItemStack item(Material material, String name, String... lore)
    {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(color(name));
        meta.lore(Arrays.stream(lore).map(StatsMenuItems::color).toList());
        item.setItemMeta(meta);
        return item;
    }

    static ItemStack item(Material material, String name, List<String> lore)
    {
        return item(material, name, lore.toArray(String[]::new));
    }

    static Component color(String value)
    {
        return LEGACY.deserialize(value);
    }

    static String duration(Long millis)
    {
        if (millis == null)
            return "No combat";
        long seconds = Math.max(0, millis / 1000L);
        if (seconds < 60)
            return seconds + "s";
        return String.format("%d:%02d", seconds / 60, seconds % 60);
    }

    static String date(long millis)
    {
        return DATE.format(Instant.ofEpochMilli(millis));
    }

    static String friendlyEnum(String value)
    {
        if (value == null)
            return "Unknown";
        String[] words = value.toLowerCase().split("_");
        for (int i = 0; i < words.length; i++)
            words[i] = Character.toUpperCase(words[i].charAt(0)) + words[i].substring(1);
        return String.join(" ", words);
    }
}
