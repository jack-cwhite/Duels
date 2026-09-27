package me.jackcw.duels.menu.admin.kit;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitEffect;
import me.jackcw.duels.kit.KitEffectDisplay;
import me.jackcw.duels.kit.KitEffectFeedback;
import me.jackcw.duels.kit.KitEffectMutationResult;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.menu.PaginatedMenuBuilder;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Registry-driven list of every usable potion effect type, sorted for deterministic pages. */
public final class KitEffectsMenu
{
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final MenuManager menus;
    private final MessageManager messageManager;
    private final KitManager kitManager;
    private final KitEffectDetailMenu kitEffectDetailMenu;
    private final List<PotionEffectType> effectTypes;

    public KitEffectsMenu(Duels plugin, KitEffectDetailMenu kitEffectDetailMenu)
    {
        this.menus = plugin.core().menus();
        this.messageManager = plugin.core().messages();
        this.kitManager = plugin.getKitManager();
        this.kitEffectDetailMenu = kitEffectDetailMenu;
        this.effectTypes = loadEffectTypes();
    }

    private static List<PotionEffectType> loadEffectTypes()
    {
        List<PotionEffectType> types = new ArrayList<>();

        for (PotionEffectType type : Registry.MOB_EFFECT)
            if (!type.isInstant())
                types.add(type);

        types.sort(Comparator.comparing(type -> type.getKey().toString()));

        return types;
    }

    public void open(Player player, int kitId, int page)
    {
        Kit kit = kitManager.getKit(kitId);

        if (kit == null)
        {
            messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
            player.closeInventory();

            return;
        }

        PaginatedMenuBuilder<PotionEffectType> builder = menus.paginatedMenu("kit-effects", effectTypes);

        builder.placeholders(Map.of("kit-name", kit.getName()))
                .page(page)
                .itemFactory(type -> buildItem(kit, type))
                .onClick((context, type) ->
                {
                    Kit current = kitManager.getKit(kitId);

                    if (current == null)
                    {
                        messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
                        player.closeInventory();

                        return;
                    }

                    String key = type.getKey().toString();
                    boolean wasPresent = current.getEffect(type.getKey()) != null;

                    if (context.clickType().isShiftClick() && context.clickType().isRightClick())
                    {
                        if (!wasPresent)
                            kitManager.addEffect(kitId, key, 1);

                        context.openChild(() -> kitEffectDetailMenu.open(player, kitId, key));

                        return;
                    }

                    boolean removing = !context.clickType().isRightClick() && wasPresent;

                    KitEffectMutationResult result = context.clickType().isRightClick()
                            ? kitManager.cycleEffectLevel(kitId, key)
                            : removing
                                    ? kitManager.removeEffect(kitId, key)
                                    : kitManager.addEffect(kitId, key, 1);

                    KitEffectFeedback.send(messageManager, player, kitId, key, result, removing);

                    context.reopen();
                })
                .back()
                .open(player);
    }

    public void open(Player player, int kitId)
    {
        open(player, kitId, 0);
    }

    private ItemStack buildItem(Kit kit, PotionEffectType type)
    {
        KitEffect effect = kit.getEffect(type.getKey());

        ItemStack item = new ItemStack(Material.POTION);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(color("&f" + KitEffectDisplay.name(type.getKey())));

        List<Component> lore = new ArrayList<>();

        if (effect == null)
        {
            lore.add(color("&7Not configured."));
        }
        else
        {
            lore.add(color("&aLevel " + KitEffectDisplay.level(effect.level())));
            lore.add(color("&7Ambient: &f" + effect.ambient()));
            lore.add(color("&7Particles: &f" + effect.particles()));
            lore.add(color("&7HUD icon: &f" + effect.icon()));
        }

        lore.add(Component.empty());
        lore.add(color("&aLeft click &7to add/remove"));
        lore.add(color("&eRight click &7to cycle level"));
        lore.add(color("&6Shift + right click &7for exact settings"));

        meta.lore(lore);
        item.setItemMeta(meta);

        return item;
    }

    private static Component color(String text)
    {
        return LEGACY.deserialize(text);
    }
}
