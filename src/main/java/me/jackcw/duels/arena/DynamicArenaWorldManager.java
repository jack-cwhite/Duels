package me.jackcw.duels.arena;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;

import java.io.File;
import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import java.util.logging.Logger;

/** Creates or verifies the one world owned by Duels for provisioned arenas. */
public final class DynamicArenaWorldManager
{
    private final DynamicArenaSlotManager slotManager;
    private final Logger logger;
    private final Set<Integer> visibilityWarnings = new HashSet<>();

    public DynamicArenaWorldManager(DynamicArenaSlotManager slotManager, Logger logger)
    {
        this.slotManager = slotManager;
        this.logger = logger;
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
     * Warns when this arena's real captured footprint can enter the chunk-send
     * range of an adjacent slot. This is advisory rather than validation:
     * neighbouring arenas may deliberately be visible, and changing persisted
     * grid geometry automatically would move existing copies.
     */
    public void warnIfAdjacentSlotsMayBeVisible(World world, int arenaId, ArenaStructureSize size)
    {
        DynamicArenaLayout layout = slotManager.getOrCreateLayout();
        OptionalInt separation = layout.minimumAdjacentSeparation(size);

        if (separation.isEmpty())
            return;

        int sendDistanceChunks = world.getSendViewDistance();
        if (sendDistanceChunks < 0)
            sendDistanceChunks = world.getViewDistance();

        int sendDistanceBlocks = sendDistanceChunks * 16;
        if (!layout.mayExposeAdjacentSlot(size, sendDistanceChunks) || !visibilityWarnings.add(arenaId))
            return;

        logger.warning("Dynamic arena #" + arenaId + " can be only " + separation.getAsInt()
                + " blocks from the next slot, while world '" + world.getName() + "' sends chunks up to "
                + sendDistanceChunks + " chunks (approximately " + sendDistanceBlocks + " blocks). Players near an edge may see "
                + "a neighbouring arena. Increase dynamic-arenas.slot-padding before first use, rebuild the generated pool with a "
                + "larger layout, reduce the world's send distance, or accept that arenas may be visible.");
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
