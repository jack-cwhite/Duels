package me.jackcw.duels.menu.admin.kit;

import me.jackcw.duels.Duels;
import me.jackcw.duels.kit.Kit;
import me.jackcw.duels.kit.KitEffect;
import me.jackcw.duels.kit.KitEffectDisplay;
import me.jackcw.duels.kit.KitEffectFeedback;
import me.jackcw.duels.kit.KitEffectMutationResult;
import me.jackcw.duels.kit.KitManager;
import me.jackcw.duels.message.Message;
import me.jackcw.jcore.menu.ConfiguredMenu;
import me.jackcw.jcore.menu.MenuContext;
import me.jackcw.jcore.menu.MenuManager;
import me.jackcw.jcore.message.MessageManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Map;

/** Exact-value editing for one already-configured kit effect. */
public final class KitEffectDetailMenu
{
    private final MenuManager menus;
    private final MessageManager messageManager;
    private final KitManager kitManager;

    public KitEffectDetailMenu(Duels plugin)
    {
        this.menus = plugin.core().menus();
        this.messageManager = plugin.core().messages();
        this.kitManager = plugin.getKitManager();
    }

    public void open(Player player, int kitId, String typeKey)
    {
        Kit kit = kitManager.getKit(kitId);

        if (kit == null)
        {
            messageManager.send(player, Message.KIT_NOT_FOUND, "id", kitId);
            player.closeInventory();

            return;
        }

        NamespacedKey key = NamespacedKey.fromString(typeKey);
        KitEffect effect = key == null ? null : kit.getEffect(key);

        if (effect == null)
        {
            messageManager.send(player, Message.KIT_EFFECT_NOT_FOUND, "id", kitId, "type", typeKey);
            player.closeInventory();

            return;
        }

        String displayName = KitEffectDisplay.name(effect.typeKey());

        ConfiguredMenu menu = menus.menu("kit-effect-detail")
                .placeholders(Map.of(
                        "kit-name", kit.getName(),
                        "effect-name", displayName,
                        "level", KitEffectDisplay.level(effect.level())))
                .item("level-down", context -> adjustLevel(player, kitId, typeKey, -1, context))
                .item("level-up", context -> adjustLevel(player, kitId, typeKey, 1, context))
                .item("exact-level", context -> requestExactLevel(player, kitId, typeKey, context))
                .item("ambient", !effect.ambient(), context ->
                        toggleFlag(player, kitId, typeKey, KitManager.EffectFlag.AMBIENT, effect.ambient(), context))
                .item("particles", !effect.particles(), context ->
                        toggleFlag(player, kitId, typeKey, KitManager.EffectFlag.PARTICLES, effect.particles(), context))
                .item("icon", !effect.icon(), context ->
                        toggleFlag(player, kitId, typeKey, KitManager.EffectFlag.ICON, effect.icon(), context))
                .item("remove", context ->
                {
                    KitEffectMutationResult result = kitManager.removeEffect(kitId, typeKey);
                    KitEffectFeedback.send(messageManager, player, kitId, typeKey, result, true);

                    if (result.isSuccess())
                        context.back();
                    else
                        context.reopen();
                })
                .back();

        menu.open(player);
    }

    private void adjustLevel(Player player, int kitId, String typeKey, int delta, MenuContext context)
    {
        Kit kit = kitManager.getKit(kitId);
        NamespacedKey key = NamespacedKey.fromString(typeKey);
        KitEffect existing = kit == null || key == null ? null : kit.getEffect(key);

        if (existing == null)
        {
            messageManager.send(player, Message.KIT_EFFECT_NOT_FOUND, "id", kitId, "type", typeKey);
            player.closeInventory();

            return;
        }

        int nextLevel = Math.max(1, Math.min(255, existing.level() + delta));

        KitEffectMutationResult result = kitManager.setEffectLevel(kitId, typeKey, nextLevel);
        KitEffectFeedback.send(messageManager, player, kitId, typeKey, result, false);

        context.reopen();
    }

    private void requestExactLevel(Player player, int kitId, String typeKey, MenuContext context)
    {
        context.requestInput(
                prompt(Message.KIT_EFFECT_LEVEL_PROMPT),
                input ->
                {
                    int level;

                    try
                    {
                        level = Integer.parseInt(input.trim());
                    }
                    catch (NumberFormatException exception)
                    {
                        messageManager.send(player, Message.KIT_EFFECT_INVALID_LEVEL);
                        context.reopen();

                        return;
                    }

                    KitEffectMutationResult result = kitManager.setEffectLevel(kitId, typeKey, level);
                    KitEffectFeedback.send(messageManager, player, kitId, typeKey, result, false);

                    context.reopen();
                },
                () ->
                {
                    messageManager.send(player, Message.MENU_INPUT_CANCELLED);
                    context.reopen();
                });
    }

    private void toggleFlag(Player player, int kitId, String typeKey, KitManager.EffectFlag flag,
                            boolean current, MenuContext context)
    {
        KitEffectMutationResult result = kitManager.setEffectFlag(kitId, typeKey, flag, !current);
        KitEffectFeedback.send(messageManager, player, kitId, typeKey, result, false);

        context.reopen();
    }

    private Component prompt(Message key)
    {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messageManager.format(key));
    }
}
