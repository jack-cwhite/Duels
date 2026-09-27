package me.jackcw.duels.kit;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.potion.PotionEffectType;

/** A permanent effect baseline belonging to the holder of one kit. */
public record KitEffect(NamespacedKey typeKey, int level, boolean ambient, boolean particles, boolean icon)
{
    public KitEffect
    {
        if (typeKey == null)
            throw new IllegalArgumentException("Effect type cannot be null");

        PotionEffectType type = Registry.MOB_EFFECT.get(typeKey);
        if (type == null)
            throw new IllegalArgumentException("Unknown effect type '" + typeKey + "'");
        if (type.isInstant())
            throw new IllegalArgumentException("Instant effect type '" + typeKey + "' cannot be a kit baseline");
        if (level < 1 || level > 255)
            throw new IllegalArgumentException("Effect level must be between 1 and 255");
    }

    public PotionEffectType resolveType()
    {
        return Registry.MOB_EFFECT.get(typeKey);
    }
}
