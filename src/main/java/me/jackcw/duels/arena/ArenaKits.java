package me.jackcw.duels.arena;

import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ArenaKits
{
    private ArenaKits()
    {
    }

    public static List<Kit> allowedKits(Arena arena, KitManager kitManager)
    {
        List<Kit> allowed = new ArrayList<>();

        for (Kit kit : kitManager.getKits())
            if (arena.isKitAllowed(kit))
                allowed.add(kit);

        allowed.sort(Comparator.comparingInt(Kit::getId));

        return allowed;
    }
}
