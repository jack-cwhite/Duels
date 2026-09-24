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

        alignWithPrimaryWorld(world);

        slotManager.setWorldId(world.getUID());
        return world;
    }

    /**
     * Makes a duel in a provisioned arena play exactly like a duel in a
     * hand-built one.
     *
     * <p>{@code WorldCreator} does not inherit difficulty or PVP from
     * server.properties, and a world generated without them is written to disk
     * as Peaceful. That is not a cosmetic difference: on Peaceful a player
     * regenerates health continuously, never gets hungry, and - the part that
     * cost three rounds of live testing to find - takes no explosion damage at
     * all. TNT in a dynamic arena destroyed blocks and hurt nobody, and because
     * vanilla skips the damage entirely no {@code EntityDamageEvent} was ever
     * raised for Duels to see.
     *
     * <p>The primary world is the reference rather than a config option because
     * there is no sensible answer other than "whatever the rest of this server
     * does" - an admin who wants Hard duels wants a Hard server. It is
     * reapplied on every load, not just on creation, so a world already written
     * out as Peaceful is repaired rather than left broken forever.
     */
    private void alignWithPrimaryWorld(World world)
    {
        World primary = Bukkit.getWorlds().get(0);

        if (primary.equals(world))
            return;

        world.setDifficulty(primary.getDifficulty());
        world.setPVP(primary.getPVP());
    }
}
