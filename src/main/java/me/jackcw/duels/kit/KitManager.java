package me.jackcw.duels.kit;

import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class KitManager
{
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
}
