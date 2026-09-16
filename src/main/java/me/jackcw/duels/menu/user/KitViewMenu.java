package me.jackcw.duels.menu.user;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

public final class KitViewMenu
{
    private static final int CONTENT_SIZE = 36;

    private static final int HELMET_SLOT = 38;
    private static final int CHESTPLATE_SLOT = 39;
    private static final int LEGGINGS_SLOT = 40;
    private static final int BOOTS_SLOT = 41;
    private static final int OFFHAND_SLOT = 43;

    private final MenuManager menus;
    private final MessageManager messageManager;
    private final KitManager kitManager;

    public KitViewMenu(Duels plugin)
    {
        this.menus = plugin.getJCore().menus();
        this.messageManager = plugin.getJCore().messages();
        this.kitManager = plugin.getKitManager();
    }

    public void open(Player player, int kitId)
    {
        Kit kit = kitManager.getKit(kitId);

        if (kit == null)
        {
            messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
            player.closeInventory();

            return;
        }

        render(player, kit);
    }

    public void open(Player player, Kit kit)
    {
        if (kit == null)
        {
            player.closeInventory();
            return;
        }

        render(player, kit);
    }

    private void render(Player player, Kit kit)
    {
        var menu = menus.menu("kit-view").placeholders(Map.of("kit-name", kit.getName()));

        ItemStack[] contents = kit.getContents();
        ItemStack[] armor = kit.getArmor();

        for (int slot = 0; slot < CONTENT_SIZE; slot++)
            menu.builder().item(slot, itemAt(contents, slot));

        menu.builder().item(BOOTS_SLOT, itemAt(armor, 0));
        menu.builder().item(LEGGINGS_SLOT, itemAt(armor, 1));
        menu.builder().item(CHESTPLATE_SLOT, itemAt(armor, 2));
        menu.builder().item(HELMET_SLOT, itemAt(armor, 3));
        menu.builder().item(OFFHAND_SLOT, itemAt(new ItemStack[] { kit.getOffHand() }, 0));

        menu.back().open(player);
    }

    private ItemStack itemAt(ItemStack[] items, int index)
    {
        if (items == null || index < 0 || index >= items.length)
            return null;

        ItemStack item = items[index];
        return item != null && item.getType() != Material.AIR ? item.clone() : null;
    }
}
