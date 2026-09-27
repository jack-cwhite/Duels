package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import me.jackcw.jcore.storage.YamlFile;

import java.nio.file.Files;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Owns dynamic-layout.yml and short-lived slot reservations. Occupancy itself
 * is reconstructed from persisted provisioned instances, not duplicated here.
 */
public final class DynamicArenaSlotManager
{
    /**
     * One read-only view of the bounded dynamic-world grid.
     *
     * <p>Occupied slots contain persisted generated-copy records, including
     * copies that are ready, active, failed, dirty, or retiring. Reserved slots
     * are short-lived claims made before a new record is persisted. Keeping the
     * two separate makes "the pool is full" diagnosable without exposing the
     * manager's mutable sets.
     */
    public record Capacity(int occupied, int reserved, int maximum)
    {
        public int available()
        {
            return maximum - occupied - reserved;
        }
    }

    private final Duels plugin;
    private final Set<Integer> reservedSlots = new HashSet<>();
    private final Set<Integer> occupiedSlots = new HashSet<>();
    private boolean recoveryBlocked;
    private DynamicArenaLayout layout;
    private YamlFile layoutFile;

    public DynamicArenaSlotManager(Duels plugin) { this.plugin = plugin; }

    /**
     * True once a dynamic arena world has actually been provisioned before.
     * Lets callers eagerly load that world before anything tries to deserialize
     * instance records that reference it, instead of leaving Bukkit to load it
     * lazily on first provision - which is too late for records already on disk.
     */
    public boolean hasPersistedLayout()
    {
        if (!Files.isRegularFile(plugin.getDataFolder().toPath().resolve("dynamic-layout.yml")))
            return false;

        return plugin.core().files().yaml("dynamic-layout.yml").contains("version");
    }

    public DynamicArenaLayout getOrCreateLayout()
    {
        if (layout != null)
            return layout;

        layoutFile = plugin.core().files().yaml("dynamic-layout.yml");
        if (!layoutFile.contains("version"))
        {
            layout = DynamicArenaLayout.fromSettings(plugin.getSettings().dynamicArenas());
            saveLayout();
            return layout;
        }

        layout = new DynamicArenaLayout(layoutFile.getInt("version"), layoutFile.getString("world-name"),
                parseWorldId(layoutFile.getString("world-id")), layoutFile.getInt("max-slots"),
                layoutFile.getInt("slots-per-row"), layoutFile.getInt("slot-width"), layoutFile.getInt("slot-length"),
                layoutFile.getInt("slot-padding"), layoutFile.getInt("base-y"));
        return layout;
    }

    public void setWorldId(UUID worldId)
    {
        DynamicArenaLayout current = getOrCreateLayout();
        if (current.worldId() != null && !current.worldId().equals(worldId))
            throw new IllegalStateException("Dynamic arena world identity cannot be changed");
        if (current.worldId() == null)
        {
            layout = current.withWorldId(worldId);
            saveLayout();
        }
    }

    public DynamicArenaSlot reserveNext()
    {
        if (recoveryBlocked)
            return null;
        DynamicArenaLayout current = getOrCreateLayout();
        for (int index = 0; index < current.maxSlots(); index++)
            if (!occupiedSlots.contains(index) && !reservedSlots.contains(index))
            {
                reservedSlots.add(index);
                return current.slot(index);
            }
        return null;
    }

    public void releaseReservation(int slotIndex) { reservedSlots.remove(slotIndex); }

    public void markOccupied(int slotIndex)
    {
        getOrCreateLayout().slot(slotIndex);
        reservedSlots.remove(slotIndex);
        occupiedSlots.add(slotIndex);
    }

    public void markVacant(int slotIndex)
    {
        occupiedSlots.remove(slotIndex);
        reservedSlots.remove(slotIndex);
    }

    public void reconstructOccupiedSlots(Collection<Integer> slotIndexes)
    {
        recoveryBlocked = true;
        DynamicArenaLayout current = getOrCreateLayout();
        Set<Integer> reconstructed = new HashSet<>();
        for (int slotIndex : slotIndexes)
        {
            current.slot(slotIndex);
            if (!reconstructed.add(slotIndex))
                throw new IllegalStateException("Duplicate persisted dynamic arena slot " + slotIndex);
        }
        occupiedSlots.clear();
        occupiedSlots.addAll(reconstructed);
        recoveryBlocked = false;
    }

    public void blockProvisioning() { recoveryBlocked = true; }

    public boolean isOccupied(int slotIndex) { return occupiedSlots.contains(slotIndex); }

    /**
     * Reports capacity without creating {@code dynamic-layout.yml} on a
     * static-only installation. Once a layout exists its persisted maximum
     * wins over config, matching the coordinates used by every existing copy.
     */
    public Capacity capacity()
    {
        int maximum;

        if (layout != null)
            maximum = layout.maxSlots();
        else if (hasPersistedLayout())
            maximum = getOrCreateLayout().maxSlots();
        else
            maximum = plugin.getSettings().dynamicArenas().maxSlots();

        return new Capacity(occupiedSlots.size(), reservedSlots.size(), maximum);
    }

    private void saveLayout()
    {
        layoutFile.set("version", layout.version());
        layoutFile.set("world-name", layout.worldName());
        layoutFile.set("world-id", layout.worldId() == null ? null : layout.worldId().toString());
        layoutFile.set("max-slots", layout.maxSlots());
        layoutFile.set("slots-per-row", layout.slotsPerRow());
        layoutFile.set("slot-width", layout.slotWidth());
        layoutFile.set("slot-length", layout.slotLength());
        layoutFile.set("slot-padding", layout.slotPadding());
        layoutFile.set("base-y", layout.baseY());
        layoutFile.save();
    }

    private static UUID parseWorldId(String raw)
    {
        if (raw == null || raw.isBlank())
            return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("Invalid dynamic arena world UUID '" + raw + "'", exception); }
    }
}
