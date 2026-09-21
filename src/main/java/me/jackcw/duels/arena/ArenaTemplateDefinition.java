package me.jackcw.duels.arena;

import java.util.Objects;

/**
 * Immutable metadata needed to turn a captured structure file into a physical
 * {@link ArenaInstance}. It intentionally contains no Bukkit world reference:
 * all coordinates are relative to the structure's minimum capture corner.
 */
public record ArenaTemplateDefinition(
        int revision,
        String providerId,
        String fileName,
        ArenaStructureSize size,
        RelativeArenaLocation spawn1,
        RelativeArenaLocation spawn2,
        RelativeBlockPosition boundsCorner1,
        RelativeBlockPosition boundsCorner2
)
{
    public ArenaTemplateDefinition
    {
        if (revision < 1)
            throw new IllegalArgumentException("Template revision must be positive");

        if (providerId == null || providerId.isBlank())
            throw new IllegalArgumentException("Template provider id cannot be blank");

        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")
                || fileName.contains(".."))
            throw new IllegalArgumentException("Template file name must be a simple file name");

        Objects.requireNonNull(size, "Template size cannot be null");
        Objects.requireNonNull(spawn1, "Template spawn 1 cannot be null");
        Objects.requireNonNull(spawn2, "Template spawn 2 cannot be null");
        Objects.requireNonNull(boundsCorner1, "Template bounds corner 1 cannot be null");
        Objects.requireNonNull(boundsCorner2, "Template bounds corner 2 cannot be null");
    }

    public boolean fitsWithin(int maxWidth, int maxHeight, int maxLength, long maxVolume)
    {
        return size.x() <= maxWidth && size.y() <= maxHeight && size.z() <= maxLength
                && size.volume() <= maxVolume;
    }
}
