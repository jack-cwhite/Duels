package me.jackcw.duels.arena;

import org.bukkit.Material;

public enum ArenaEditTool
{
    SPAWN_1(Material.LIME_DYE, "&aSpawn 1 (left: set, right: teleport)"),
    SPAWN_2(Material.LIGHT_BLUE_DYE, "&bSpawn 2 (left: set, right: teleport)"),
    BOUNDS_CORNER_1(Material.LIME_CONCRETE, "&aBounds Corner 1 (left: set, right: teleport)"),
    BOUNDS_CORNER_2(Material.LIGHT_BLUE_CONCRETE, "&bBounds Corner 2 (left: set, right: teleport)"),
    EXIT(Material.BARRIER, "&cExit Edit Mode");

    private final Material material;
    private final String displayName;

    ArenaEditTool(Material material, String displayName)
    {
        this.material = material;
        this.displayName = displayName;
    }

    public Material getMaterial()
    {
        return material;
    }

    public String getDisplayName()
    {
        return displayName;
    }
}
