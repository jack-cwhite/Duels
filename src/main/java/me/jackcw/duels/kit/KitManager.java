package me.jackcw.duels.kit;

import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class KitManager
{
    public enum EffectFlag { AMBIENT, PARTICLES, ICON }

    private final YamlRepository<Kit> repository;
    private final Map<Integer, Kit> kits = new HashMap<>();

    public KitManager(YamlRepository<Kit> repository)
    {
        this.repository = repository;

        for (Kit kit : repository.findAll())
            kits.put(kit.getId(), kit);
    }

    public Kit createKit(String name)
    {
        int id = repository.reserveId();
        Kit kit = new Kit(id, name);

        save(kit);

        return kit;
    }

    public Kit createKit(String name, Player player)
    {
        int id = repository.reserveId();
        Kit kit = new Kit(id, name);

        if (player != null)
        {
            kit.setContents(player.getInventory().getStorageContents());
            kit.setArmor(player.getInventory().getArmorContents());
            kit.setOffHand(player.getInventory().getItemInOffHand());

            if (!player.getInventory().getItemInMainHand().getType().equals(Material.AIR))
                kit.setIcon(player.getInventory().getItemInMainHand());
        }

        save(kit);

        return kit;
    }

    public boolean deleteKit(int id)
    {
        Kit kit = kits.get(id);

        if (kit == null)
            return false;

        kits.remove(id);
        repository.delete(id);

        return true;
    }

    public Kit getKit(int id)
    {
        return kits.get(id);
    }

    public List<Kit> getKits()
    {
        List<Kit> result = new ArrayList<>(kits.values());
        result.sort(Comparator.comparingInt(Kit::getId));

        return result;
    }

    public void save(Kit kit)
    {
        kits.put(kit.getId(), kit);
        repository.save(kit);
    }

    public KitEffectMutationResult addEffect(int kitId, String typeName, int level)
    {
        EffectTarget target = resolveEffect(kitId, typeName);
        if (target.status != null)
            return failed(target.status);
        if (level < 1 || level > 255)
            return failed(KitEffectMutationResult.Status.INVALID_LEVEL);
        if (target.kit.getEffect(target.key) != null)
            return failed(KitEffectMutationResult.Status.ALREADY_PRESENT);

        return store(target.kit, new KitEffect(target.key, level, false, true, true));
    }

    public KitEffectMutationResult removeEffect(int kitId, String typeName)
    {
        EffectTarget target = resolveEffect(kitId, typeName);
        if (target.status != null)
            return failed(target.status);

        KitEffect existing = target.kit.getEffect(target.key);
        if (existing == null)
            return failed(KitEffectMutationResult.Status.EFFECT_NOT_FOUND);

        target.kit.removeEffect(target.key);
        save(target.kit);
        return new KitEffectMutationResult(KitEffectMutationResult.Status.SUCCESS, existing);
    }

    public KitEffectMutationResult setEffectLevel(int kitId, String typeName, int level)
    {
        EffectTarget target = resolveEffect(kitId, typeName);
        if (target.status != null)
            return failed(target.status);
        if (level < 1 || level > 255)
            return failed(KitEffectMutationResult.Status.INVALID_LEVEL);

        KitEffect existing = target.kit.getEffect(target.key);
        if (existing == null)
            return failed(KitEffectMutationResult.Status.EFFECT_NOT_FOUND);

        return store(target.kit, new KitEffect(target.key, level,
                existing.ambient(), existing.particles(), existing.icon()));
    }

    public KitEffectMutationResult cycleEffectLevel(int kitId, String typeName)
    {
        EffectTarget target = resolveEffect(kitId, typeName);
        if (target.status != null)
            return failed(target.status);

        KitEffect existing = target.kit.getEffect(target.key);
        if (existing == null)
            return failed(KitEffectMutationResult.Status.EFFECT_NOT_FOUND);

        int next = existing.level() >= 5 ? 1 : existing.level() + 1;
        return setEffectLevel(kitId, typeName, next);
    }

    public KitEffectMutationResult setEffectFlag(int kitId, String typeName, EffectFlag flag, boolean enabled)
    {
        if (flag == null)
            throw new IllegalArgumentException("Effect flag cannot be null");

        EffectTarget target = resolveEffect(kitId, typeName);
        if (target.status != null)
            return failed(target.status);

        KitEffect existing = target.kit.getEffect(target.key);
        if (existing == null)
            return failed(KitEffectMutationResult.Status.EFFECT_NOT_FOUND);

        return store(target.kit, new KitEffect(target.key, existing.level(),
                flag == EffectFlag.AMBIENT ? enabled : existing.ambient(),
                flag == EffectFlag.PARTICLES ? enabled : existing.particles(),
                flag == EffectFlag.ICON ? enabled : existing.icon()));
    }

    private KitEffectMutationResult store(Kit kit, KitEffect effect)
    {
        kit.setEffect(effect);
        save(kit);
        return new KitEffectMutationResult(KitEffectMutationResult.Status.SUCCESS, effect);
    }

    private static KitEffectMutationResult failed(KitEffectMutationResult.Status status)
    {
        return new KitEffectMutationResult(status, null);
    }

    private EffectTarget resolveEffect(int kitId, String typeName)
    {
        Kit kit = kits.get(kitId);
        if (kit == null)
            return new EffectTarget(null, null, KitEffectMutationResult.Status.KIT_NOT_FOUND);

        NamespacedKey key = typeName == null ? null : NamespacedKey.fromString(typeName);
        if (key == null)
            return new EffectTarget(kit, null, KitEffectMutationResult.Status.INVALID_TYPE);

        PotionEffectType type = Registry.MOB_EFFECT.get(key);
        if (type == null)
            return new EffectTarget(kit, key, KitEffectMutationResult.Status.INVALID_TYPE);
        if (type.isInstant())
            return new EffectTarget(kit, key, KitEffectMutationResult.Status.INSTANT_TYPE);

        return new EffectTarget(kit, key, null);
    }

    private record EffectTarget(Kit kit, NamespacedKey key, KitEffectMutationResult.Status status) {}
}
