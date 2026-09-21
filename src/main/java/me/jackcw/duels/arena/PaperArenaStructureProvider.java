package me.jackcw.duels.arena;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Structure;
import org.bukkit.structure.StructureManager;
import org.bukkit.util.BlockVector;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Random;

/**
 * Paper's native NBT-structure implementation. Calls are intentionally made
 * only by the main-thread orchestration code: capture and placement access
 * Bukkit worlds even though their file representation is ordinary NBT.
 */
public final class PaperArenaStructureProvider implements ArenaStructureProvider
{
    public static final String ID = "paper-nbt";

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public void capture(Location origin, ArenaStructureSize size, Path target) throws IOException
    {
        StructureManager manager = Bukkit.getStructureManager();
        Structure structure = manager.createStructure();
        // The two-corner overload excludes the opposite corner on Paper.
        // An explicit size includes every block the admin selected and keeps
        // saved dimensions identical to the metadata and slot calculations.
        structure.fill(origin, new BlockVector(size.x(), size.y(), size.z()), false);
        manager.saveStructure(target.toFile(), structure);
    }

    @Override
    public ArenaStructureSize readSize(Path source) throws IOException
    {
        BlockVector size = Bukkit.getStructureManager().loadStructure(source.toFile()).getSize();
        return new ArenaStructureSize(size.getBlockX(), size.getBlockY(), size.getBlockZ());
    }

    @Override
    public void place(Path source, World world, int originX, int originY, int originZ) throws IOException
    {
        Structure structure = Bukkit.getStructureManager().loadStructure(source.toFile());
        structure.place(
                new Location(world, originX, originY, originZ), false,
                StructureRotation.NONE, Mirror.NONE, 0, 1.0F, new Random(0L)
        );
    }
}
