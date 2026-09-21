package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;

import java.util.List;
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

        try
        {
            plugin.getDynamicArenaSlotManager().reconstructOccupiedSlots(
                    instances.stream().map(ArenaInstance::getDynamicSlotIndex).toList()
            );
        }
        catch (RuntimeException exception)
        {
            plugin.getLogger().log(Level.SEVERE, "Dynamic arena slot reconstruction failed; provisioned instances remain unavailable", exception);
            return;
        }

        for (ArenaInstance instance : instances)
        {
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
