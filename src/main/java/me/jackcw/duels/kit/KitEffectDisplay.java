package me.jackcw.duels.kit;

import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class KitEffectDisplay
{
    private static final String[] ROMAN = { "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X" };

    private KitEffectDisplay() {}

    public static String name(NamespacedKey key)
    {
        String[] words = key.getKey().split("_");
        return Arrays.stream(words)
                .map(word -> word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1))
                .reduce((left, right) -> left + " " + right)
                .orElse(key.toString());
    }

    public static String level(int level)
    {
        return level >= 1 && level <= ROMAN.length ? ROMAN[level - 1] : String.valueOf(level);
    }

    /** Colour-coded summary lines for a kit's effects, shown in previews before a match starts. */
    public static List<String> previewLore(List<KitEffect> effects)
    {
        List<String> lines = new ArrayList<>();

        if (effects.isEmpty())
        {
            lines.add("&7No permanent effects.");
            return lines;
        }

        for (KitEffect effect : effects)
            lines.add("&e" + name(effect.typeKey()) + " &7" + level(effect.level()));

        return lines;
    }
}
