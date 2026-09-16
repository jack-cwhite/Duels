package me.jackcw.duels.menu.admin.kit;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;

public final class KitDetailMenu
{
    private final MenuManager menus;
    private final KitManager kitManager;
    private final MessageManager messageManager;
    private final KitEditMenu kitEditMenu;

    public KitDetailMenu(Duels plugin, KitEditMenu kitEditMenu)
    {
        this.menus = plugin.getJCore().menus();
        this.kitManager = plugin.getKitManager();
        this.messageManager = plugin.getJCore().messages();
        this.kitEditMenu = kitEditMenu;
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

        menus.menu("kit-detail")
                .placeholders(Map.of("kit-name", kit.getName()))
                .item("set-icon", context ->
                {
                    Kit current = requireKit(player, kitId, context);

                    if (current == null)
                        return;

                    ItemStack held = player.getInventory().getItemInMainHand();

                    if (held.getType() == Material.AIR)
                    {
                        messageManager.send(player, Message.KIT_ICON_EMPTY_HAND);
                        context.reopen();

                        return;
                    }

                    current.setIcon(held.clone());
                    kitManager.save(current);

                    messageManager.send(player, Message.KIT_ICON_SET, "name", current.getName());

                    context.reopen();
                })
                .item("rename", context ->
                {
                    Kit current = requireKit(player, kitId, context);

                    if (current == null)
                        return;

                    context.requestInput(
                            prompt(Message.KIT_RENAME_PROMPT),
                            name ->
                            {
                                Kit target = requireKit(player, kitId, context);

                                if (target == null)
                                    return;

                                target.setName(name);
                                kitManager.save(target);

                                messageManager.send(player, Message.KIT_RENAMED, "id", kitId, "name", name);

                                context.reopen();
                            },
                            () ->
                            {
                                messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                                context.reopen();
                            }
                    );
                })
                .item("edit", context ->
                {
                    Kit current = requireKit(player, kitId, context);

                    if (current == null)
                        return;

                    context.openChild(() -> kitEditMenu.open(player, kitId));
                })
                .item("delete", context ->
                {
                    Kit current = requireKit(player, kitId, context);

                    if (current == null)
                        return;

                    context.openChild(() -> menus.confirm()
                            .title("&8Delete Kit?")
                            .description(List.of(
                                    "&cDelete kit '" + current.getName() + "'?",
                                    "&cThis cannot be undone."
                            ))
                            .onConfirm(confirmContext ->
                            {
                                if (kitManager.getKit(kitId) == null)
                                {
                                    messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
                                    player.closeInventory();

                                    return;
                                }

                                kitManager.deleteKit(kitId);
                                messageManager.send(player, Message.KIT_DELETED, "id", kitId);

                                if (!confirmContext.back(2))
                                    player.closeInventory();
                            })
                            .open(player));
                })
                .back()
                .open(player);
    }

    public void open(Player player, Kit kit)
    {
        open(player, kit.getId());
    }

    private Kit requireKit(Player player, int kitId, MenuContext context)
    {
        Kit kit = kitManager.getKit(kitId);

        if (kit == null)
        {
            messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
            context.back();
        }

        return kit;
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
