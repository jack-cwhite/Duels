package me.jackcw.duels.kit;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class KitTest
{
    @BeforeEach
    void setup()
    {
        MockBukkit.mock();
    }

    @AfterEach
    void cleanup()
    {
        MockBukkit.unmock();
    }

    @Test
    void copyDoesNotShareMutableItemsWithSource()
    {
        Kit source = new Kit(4, "Sword");
        ItemStack[] contents = new ItemStack[36];
        contents[0] = new ItemStack(Material.IRON_SWORD);
        source.setContents(contents);
        source.setOffHand(new ItemStack(Material.SHIELD));

        Kit copy = source.copy();
        source.getContents()[0].setType(Material.WOODEN_SWORD);
        source.getOffHand().setType(Material.TORCH);

        assertNotSame(source.getContents(), copy.getContents());
        assertEquals(Material.IRON_SWORD, copy.getContents()[0].getType());
        assertEquals(Material.SHIELD, copy.getOffHand().getType());
    }
}
