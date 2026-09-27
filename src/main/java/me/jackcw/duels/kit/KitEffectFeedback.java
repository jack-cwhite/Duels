package me.jackcw.duels.kit;

import me.jackcw.duels.message.Message;
import me.jackcw.jcore.message.MessageManager;
import org.bukkit.command.CommandSender;

/** Keeps command and menu mutation errors consistent. */
public final class KitEffectFeedback
{
    private KitEffectFeedback() {}

    public static void send(MessageManager messages, CommandSender sender, int kitId, String requestedType,
                            KitEffectMutationResult result, boolean removed)
    {
        switch (result.status())
        {
            case KIT_NOT_FOUND -> messages.send(sender, Message.KIT_NOT_FOUND, "id", kitId);
            case EFFECT_NOT_FOUND -> messages.send(sender, Message.KIT_EFFECT_NOT_FOUND, "id", kitId, "type", requestedType);
            case ALREADY_PRESENT -> messages.send(sender, Message.KIT_EFFECT_ALREADY_PRESENT, "id", kitId, "type", requestedType);
            case INVALID_TYPE -> messages.send(sender, Message.KIT_EFFECT_INVALID_TYPE, "type", requestedType);
            case INSTANT_TYPE -> messages.send(sender, Message.KIT_EFFECT_INSTANT_TYPE, "type", requestedType);
            case INVALID_LEVEL -> messages.send(sender, Message.KIT_EFFECT_INVALID_LEVEL);
            case SUCCESS ->
            {
                KitEffect effect = result.effect();
                String name = KitEffectDisplay.name(effect.typeKey());
                if (removed)
                    messages.send(sender, Message.KIT_EFFECT_REMOVED, "id", kitId, "type", name);
                else
                    messages.send(sender, Message.KIT_EFFECT_UPDATED,
                            "id", kitId, "type", name, "level", KitEffectDisplay.level(effect.level()),
                            "ambient", effect.ambient(), "particles", effect.particles(), "icon", effect.icon());
            }
        }
    }
}
