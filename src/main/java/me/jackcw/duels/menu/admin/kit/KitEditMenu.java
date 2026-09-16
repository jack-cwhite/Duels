package me.jackcw.duels.menu.admin.kit;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.menu.InventoryEditSession;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;

public final class KitEditMenu
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

    public KitEditMenu(Duels plugin)
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

        InventoryEditSession editSession = InventoryEditSession.capture(player);

        ConfiguredMenu menu = menus.menu("kit-edit").placeholders(Map.of("kit-name", kit.getName()));

        menu.builder().onClose(ignored -> editSession.restore(player));

        ItemStack[] contents = kit.getContents();
        ItemStack[] armor = kit.getArmor();

        for (int slot = 0; slot < CONTENT_SIZE; slot++)
            menu.builder().editableSlot(slot, itemAt(contents, slot));

        menu.builder().editableSlot(BOOTS_SLOT, itemAt(armor, 0), KitEditMenu::isBoots);
        menu.builder().editableSlot(LEGGINGS_SLOT, itemAt(armor, 1), KitEditMenu::isLeggings);
        menu.builder().editableSlot(CHESTPLATE_SLOT, itemAt(armor, 2), KitEditMenu::isChestplate);
        menu.builder().editableSlot(HELMET_SLOT, itemAt(armor, 3), KitEditMenu::isHelmet);
        menu.builder().editableSlot(OFFHAND_SLOT, cloneOrNull(kit.getOffHand()));

        menu.builder().fill(filler());

        menu.item("save", context -> save(player, kitId, context))
                .back()
                .open(player);
    }

    public void open(Player player, Kit kit)
    {
        open(player, kit.getId());
    }

    private void save(Player player, int kitId, MenuContext context)
    {
        Kit kit = kitManager.getKit(kitId);

        if (kit == null)
        {
            messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
            context.back();

            return;
        }

        Inventory inventory = context.event().getView().getTopInventory();

        ItemStack[] contents = new ItemStack[CONTENT_SIZE];

        for (int slot = 0; slot < CONTENT_SIZE; slot++)
            contents[slot] = cloneOrNull(inventory.getItem(slot));

        ItemStack[] armor = {
                cloneOrNull(inventory.getItem(BOOTS_SLOT)),
                cloneOrNull(inventory.getItem(LEGGINGS_SLOT)),
                cloneOrNull(inventory.getItem(CHESTPLATE_SLOT)),
                cloneOrNull(inventory.getItem(HELMET_SLOT))
        };

        kit.setContents(contents);
        kit.setArmor(armor);
        kit.setOffHand(cloneOrNull(inventory.getItem(OFFHAND_SLOT)));

        kitManager.save(kit);
        messageManager.send(player, Message.KIT_SAVED, "id", kit.getId(), "name", kit.getName());

        context.back();
    }

    private ItemStack itemAt(ItemStack[] items, int index)
    {
        if (items == null || index < 0 || index >= items.length)
            return null;

        return cloneOrNull(items[index]);
    }

    private ItemStack cloneOrNull(ItemStack item)
    {
        return item != null && item.getType() != Material.AIR ? item.clone() : null;
    }

    private ItemStack filler()
    {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();

        meta.displayName(Component.text(" "));
        pane.setItemMeta(meta);

        return pane;
    }

    private static boolean isHelmet(ItemStack item)
    {
        String name = item.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_HEAD") || name.endsWith("_SKULL") || item.getType() == Material.CARVED_PUMPKIN;
    }

    private static boolean isChestplate(ItemStack item)
    {
        return item.getType().name().endsWith("_CHESTPLATE") || item.getType() == Material.ELYTRA;
    }

    private static boolean isLeggings(ItemStack item)
    {
        return item.getType().name().endsWith("_LEGGINGS");
    }

    private static boolean isBoots(ItemStack item)
    {
        return item.getType().name().endsWith("_BOOTS");
    }
}
