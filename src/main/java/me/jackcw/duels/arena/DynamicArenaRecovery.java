package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Level;

/** Reconstructs dynamic slot occupancy and repairs interrupted operations on startup. */
public final class DynamicArenaRecovery
{
    private final Duels plugin;

    public DynamicArenaRecovery(Duels plugin) { this.plugin = plugin; }

    public void recover()
    {
        List<ArenaInstance> instances = plugin.getArenaInstanceManager().getProvisionedInstances();
        if (instances.isEmpty())
            return; // Static-only installs never create the layout/world.

        Map<Integer, List<ArenaInstance>> bySlot = new LinkedHashMap<>();
        for (ArenaInstance instance : instances)
            bySlot.computeIfAbsent(instance.getDynamicSlotIndex(), ignored -> new ArrayList<>()).add(instance);

        Path backup = null;
        for (Map.Entry<Integer, List<ArenaInstance>> entry : bySlot.entrySet())
        {
            List<ArenaInstance> copies = entry.getValue();
            if (copies.size() < 2)
                continue;

            ArenaInstance survivor = copies.getFirst();
            boolean identical = copies.stream().skip(1)
                    .allMatch(duplicate -> ArenaInstanceManager.identicalReadyCopy(survivor, duplicate));
            if (!identical)
            {
                plugin.getLogger().severe("Conflicting generated copies " + copies.stream().map(ArenaInstance::getId).toList()
                        + " claim slot " + entry.getKey() + "; provisioning is blocked. No records or world blocks were removed.");
                plugin.getDynamicArenaSlotManager().blockProvisioning();
                return;
            }

            try
            {
                if (backup == null)
                {
                    Path source = plugin.getDataFolder().toPath().resolve("arena-instances.yml");
                    backup = source.resolveSibling("arena-instances-before-duplicate-repair-" + UUID.randomUUID() + ".yml");
                    Files.copy(source, backup);
                }
                for (ArenaInstance duplicate : copies.subList(1, copies.size()))
                {
                    if (!plugin.getArenaInstanceManager().discardIdenticalDuplicate(survivor, duplicate))
                        throw new IllegalStateException("Duplicate record #" + duplicate.getId() + " changed during repair");
                    plugin.getLogger().warning("Removed identical duplicate generated-copy record #" + duplicate.getId()
                            + " for slot " + entry.getKey() + "; kept #" + survivor.getId()
                            + ". Original arena-instances.yml is backed up at " + backup);
                }
            }
            catch (IOException | RuntimeException exception)
            {
                plugin.getDynamicArenaSlotManager().blockProvisioning();
                plugin.getLogger().log(Level.SEVERE, "Could not safely repair duplicate dynamic slot " + entry.getKey()
                        + "; provisioning is blocked. Backup: " + Objects.toString(backup, "not created"), exception);
                return;
            }
        }

        instances = plugin.getArenaInstanceManager().getProvisionedInstances();

        try
        {
            plugin.getDynamicArenaSlotManager().reconstructOccupiedSlots(
                    instances.stream().map(ArenaInstance::getDynamicSlotIndex).toList()
            );
        }
        catch (RuntimeException exception)
        {
            plugin.getDynamicArenaSlotManager().blockProvisioning();
            plugin.getLogger().log(Level.SEVERE, "Dynamic arena slot reconstruction failed; provisioned instances remain unavailable", exception);
            return;
        }

        var world = plugin.getServer().getWorld(plugin.getDynamicArenaSlotManager().getOrCreateLayout().worldName());

        for (ArenaInstance instance : instances)
        {
            if (world != null)
                plugin.getDynamicArenaWorldManager().warnIfAdjacentSlotsMayBeVisible(
                        world, instance.getArenaId(), instance.getStructureSize());

            switch (instance.getDynamicState())
            {
                case READY, FAILED -> { }
                case PROVISIONING, DIRTY -> plugin.getDynamicArenaProvisioner().rebuild(instance)
                        .exceptionally(exception ->
                        {
                            plugin.getLogger().log(Level.WARNING, "Could not rebuild dynamic arena instance #" + instance.getId(), exception);
                            return DynamicArenaProvisionResult.failure(DynamicArenaProvisionResult.Status.FAILED);
                        });
                case RETIRING -> plugin.getDynamicArenaProvisioner().retire(instance);
            }
        }
    }
}
