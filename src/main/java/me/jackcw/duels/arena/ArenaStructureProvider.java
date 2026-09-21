package me.jackcw.duels.arena;

import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The deliberately narrow boundary around a stored arena structure format.
 *
 * <p>The Paper implementation is the dependency-free default. Keeping this
 * boundary here is justified by a real future variation: an optional
 * WorldEdit/FAWE provider can later support imported schematic files without
 * leaking either API into arena allocation or match lifecycle code.
 */
public interface ArenaStructureProvider
{
    String id();

    void capture(Location corner1, Location corner2, Path target) throws IOException;

    ArenaStructureSize readSize(Path source) throws IOException;

    void place(Path source, World world, int originX, int originY, int originZ) throws IOException;
}
