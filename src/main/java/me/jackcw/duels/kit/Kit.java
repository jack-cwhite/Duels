package me.jackcw.duels.kit;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class Kit
{
    private final int id;
    private String name;
    private ItemStack[] contents;
    private ItemStack[] armor;
    private ItemStack offHand;
    private ItemStack icon;

    public Kit(int id, String name)
    {
        this.id = id;
        this.name = name;
    }

    public int getId()
    {
        return id;
    }

    public String getName()
    {
        return name;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public ItemStack[] getContents()
    {
        return contents;
    }

    public void setContents(ItemStack[] contents)
    {
        this.contents = contents;
    }

    public ItemStack[] getArmor()
    {
        return armor;
    }

    public void setArmor(ItemStack[] armor)
    {
        this.armor = armor;
    }

    public ItemStack getOffHand()
    {
        return offHand;
    }

    public void setOffHand(ItemStack offHand)
    {
        this.offHand = offHand;
    }

    public ItemStack getIcon()
    {
        return icon;
    }

    public void setIcon(ItemStack icon)
    {
        this.icon = icon;
    }

    public void apply(Player player)
    {
        player.getInventory().setStorageContents(contents != null ? contents : new ItemStack[36]);

        player.getInventory().setArmorContents(armor != null ? armor : new ItemStack[4]);

        player.getInventory().setItemInOffHand(offHand != null ? offHand : new ItemStack(Material.AIR));
    }

    public Kit copy()
    {
        Kit copy = new Kit(id, name);

        copy.contents = cloneItems(contents);
        copy.armor = cloneItems(armor);
        copy.offHand = offHand != null ? offHand.clone() : null;
        copy.icon = icon != null ? icon.clone() : null;

        return copy;
    }

    private static ItemStack[] cloneItems(ItemStack[] items)
    {
        if (items == null)
            return null;

        ItemStack[] clone = new ItemStack[items.length];

        for (int i = 0; i < items.length; i++)
            if (items[i] != null)
                clone[i] = items[i].clone();

        return clone;
    }
}
