package me.jackcw.duels.kit;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.mockbukkit.mockbukkit.MockBukkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void matchSnapshotKeepsItsEffectsWhenLiveKitChanges()
    {
        Kit live = new Kit(4, "Scout");
        KitEffect speed = new KitEffect(NamespacedKey.minecraft("speed"), 2, false, true, true);
        KitEffect weakness = new KitEffect(NamespacedKey.minecraft("weakness"), 1, false, false, false);
        live.setEffect(speed);

        Kit snapshot = live.copy();
        live.setEffect(new KitEffect(speed.typeKey(), 3, true, false, false));
        live.setEffect(weakness);

        assertEquals(1, snapshot.getEffects().size());
        assertEquals(speed, snapshot.getEffects().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.getEffects().clear());

        snapshot.removeEffect(speed.typeKey());
        assertEquals(2, live.getEffects().size());
        assertEquals(3, live.getEffects().getFirst().level());
    }
}
