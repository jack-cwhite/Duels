package me.jackcw.duels.arena;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;

/** Creates or verifies the one world owned by Duels for provisioned arenas. */
public final class DynamicArenaWorldManager
{
    private final DynamicArenaSlotManager slotManager;

    public DynamicArenaWorldManager(DynamicArenaSlotManager slotManager)
    {
        this.slotManager = slotManager;
    }

    public World getOrCreateWorld()
    {
        DynamicArenaLayout layout = slotManager.getOrCreateLayout();
        World world = Bukkit.getWorld(layout.worldName());
        if (world == null)
        {
            File folder = new File(Bukkit.getWorldContainer(), layout.worldName());
            if (layout.worldId() != null && !folder.exists())
                throw new IllegalStateException("Dynamic arena world '" + layout.worldName() + "' is missing; refusing to recreate it over an ambiguous layout");
            if (layout.worldId() == null && folder.exists())
                throw new IllegalStateException("Dynamic arena world folder '" + layout.worldName() + "' already exists but is not owned by Duels");
            world = WorldCreator.name(layout.worldName()).generator(new VoidArenaChunkGenerator()).generateStructures(false).createWorld();
        }

        if (world == null)
            throw new IllegalStateException("Paper could not load dynamic arena world '" + layout.worldName() + "'");
        if (layout.worldId() != null && !layout.worldId().equals(world.getUID()))
            throw new IllegalStateException("Dynamic arena world UUID does not match its persisted layout");

        slotManager.setWorldId(world.getUID());
        return world;
    }
}
