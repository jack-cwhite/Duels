package me.jackcw.duels.menu.admin.kit;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

public final class KitMainMenu
{
    private final MenuManager menus;
    private final MessageManager messageManager;
    private final KitManager kitManager;
    private final KitListMenu kitListMenu;

    public KitMainMenu(Duels plugin, KitListMenu kitListMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.messageManager = plugin.getJCore().messages();
        this.kitManager = plugin.getKitManager();
        this.kitListMenu = kitListMenu;
    }

    public void open(Player player)
    {
        menus.menu("kit-main")
                .item("list", context -> context.openChild(() -> kitListMenu.open(player)))
                .item("create", context -> context.requestInput(
                        prompt(Message.KIT_CREATE_PROMPT),
                        name ->
                        {
                            Kit kit = kitManager.createKit(name);
                            messageManager.send(player, Message.KIT_CREATED, "name", name, "id", kit.getId());

                            context.reopen();
                        },
                        () ->
                        {
                            messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                            context.reopen();
                        }))
                .back()
                .open(player);
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
