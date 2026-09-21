package me.jackcw.duels.arena;

import me.jackcw.duels.Duels;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Owns template files and the metadata publication step.
 *
 * <p>Capture writes to a temporary file, validates that Paper can read the
 * saved structure, then atomically publishes the file before saving the arena
 * metadata. A failed write therefore leaves the previous working revision
 * untouched and never makes a broken template look ready.
 */
public final class ArenaTemplateManager
{
    private final Duels plugin;
    private final ArenaManager arenaManager;
    private final ArenaInstanceManager instanceManager;
    private final ArenaStructureProvider provider;
    private final Path structuresDirectory;

    public ArenaTemplateManager(Duels plugin, ArenaStructureProvider provider)
    {
        this.plugin = plugin;
        this.arenaManager = plugin.getArenaManager();
        this.instanceManager = plugin.getArenaInstanceManager();
        this.provider = provider;
        this.structuresDirectory = plugin.getDataFolder().toPath().resolve("structures");
    }

    public ArenaStructureProvider getProvider()
    {
        return provider;
    }

    public Path getStructurePath(ArenaTemplateDefinition template)
    {
        return structuresDirectory.resolve(template.fileName());
    }

    public boolean isUsable(Arena arena)
    {
        ArenaTemplateDefinition template = arena.getTemplateDefinition();
        return template != null && provider.id().equals(template.providerId())
                && Files.isRegularFile(getStructurePath(template));
    }

    public ArenaTemplateCaptureResult capture(int instanceId, Location corner1, Location corner2)
    {
        ArenaInstance instance = instanceManager.getInstance(instanceId);

        if (instance == null)
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.INSTANCE_NOT_FOUND);

        if (instanceManager.isActive(instanceId))
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.INSTANCE_IN_USE);

        if (instance.isProvisioned())
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.PROVISIONED_SOURCE);

        Arena arena = arenaManager.getArena(instance.getArenaId());

        if (arena == null)
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.ARENA_NOT_FOUND);

        if (arena.getProvisioningMode() == ArenaProvisioningMode.DYNAMIC)
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.DYNAMIC_MODE_ACTIVE);

        if (corner1 == null || corner2 == null)
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.MISSING_CAPTURE_CORNERS);

        if (!instance.isReady())
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.INSTANCE_NOT_READY);

        if (!instance.hasBounds())
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.BOUNDS_NOT_SET);

        World world = corner1.getWorld();

        if (world == null || !world.equals(corner2.getWorld()) || !world.equals(instance.getSpawn1().getWorld())
                || !world.equals(instance.getSpawn2().getWorld()) || !world.equals(instance.getBoundsCorner1().getWorld())
                || !world.equals(instance.getBoundsCorner2().getWorld()))
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.WORLD_MISMATCH);

        Location origin = minimumCorner(world, corner1, corner2);
        Location maximum = maximumCorner(world, corner1, corner2);
        ArenaStructureSize size = new ArenaStructureSize(
                maximum.getBlockX() - origin.getBlockX() + 1,
                maximum.getBlockY() - origin.getBlockY() + 1,
                maximum.getBlockZ() - origin.getBlockZ() + 1
        );

        if (size.volume() > plugin.getSettings().dynamicArenaMaxTemplateVolume())
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.TOO_LARGE);

        if (!contains(origin, maximum, instance.getSpawn1()) || !contains(origin, maximum, instance.getSpawn2())
                || !contains(origin, maximum, instance.getBoundsCorner1()) || !contains(origin, maximum, instance.getBoundsCorner2()))
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.OUTSIDE_CAPTURE_REGION);

        int revision = arena.getTemplateDefinition() == null ? 1 : arena.getTemplateDefinition().revision() + 1;
        String fileName = "arena-" + arena.getId() + "-r" + revision + ".nbt";
        ArenaTemplateDefinition template = new ArenaTemplateDefinition(
                revision, provider.id(), fileName, size,
                RelativeArenaLocation.between(origin, instance.getSpawn1()),
                RelativeArenaLocation.between(origin, instance.getSpawn2()),
                RelativeBlockPosition.between(origin, instance.getBoundsCorner1()),
                RelativeBlockPosition.between(origin, instance.getBoundsCorner2())
        );

        try
        {
            Files.createDirectories(structuresDirectory);
            Path target = getStructurePath(template);
            Path temporary = Files.createTempFile(structuresDirectory, fileName, ".tmp");

            try
            {
                provider.capture(origin, size, temporary);
                ArenaStructureSize storedSize = provider.readSize(temporary);

                if (!storedSize.equals(size))
                    throw new IOException("Saved structure size " + storedSize + " does not match capture size " + size);

                publish(temporary, target);
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }
        }
        catch (IOException | RuntimeException exception)
        {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to capture arena template for arena #" + arena.getId() + " from instance #" + instanceId, exception);
            return ArenaTemplateCaptureResult.failure(ArenaTemplateCaptureResult.Status.CAPTURE_FAILED);
        }

        arena.setTemplateDefinition(template);
        arenaManager.save(arena);
        return ArenaTemplateCaptureResult.success(template);
    }

    public boolean clear(int arenaId)
    {
        Arena arena = arenaManager.getArena(arenaId);

        if (arena == null || arena.getTemplateDefinition() == null || arena.getProvisioningMode() == ArenaProvisioningMode.DYNAMIC
                || instanceManager.hasProvisionedInstances(arenaId))
            return false;

        Path file = getStructurePath(arena.getTemplateDefinition());
        arena.setTemplateDefinition(null);
        arenaManager.save(arena);

        try
        {
            Files.deleteIfExists(file);
        }
        catch (IOException exception)
        {
            plugin.getLogger().log(Level.WARNING, "Failed to delete cleared arena template " + file, exception);
        }

        return true;
    }

    private static void publish(Path temporary, Path target) throws IOException
    {
        try
        {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch (AtomicMoveNotSupportedException ignored)
        {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Location minimumCorner(World world, Location first, Location second)
    {
        return new Location(world,
                Math.min(first.getBlockX(), second.getBlockX()),
                Math.min(first.getBlockY(), second.getBlockY()),
                Math.min(first.getBlockZ(), second.getBlockZ()));
    }

    private static Location maximumCorner(World world, Location first, Location second)
    {
        return new Location(world,
                Math.max(first.getBlockX(), second.getBlockX()),
                Math.max(first.getBlockY(), second.getBlockY()),
                Math.max(first.getBlockZ(), second.getBlockZ()));
    }

    private static boolean contains(Location minimum, Location maximum, Location location)
    {
        return location.getBlockX() >= minimum.getBlockX() && location.getBlockX() <= maximum.getBlockX()
                && location.getBlockY() >= minimum.getBlockY() && location.getBlockY() <= maximum.getBlockY()
                && location.getBlockZ() >= minimum.getBlockZ() && location.getBlockZ() <= maximum.getBlockZ();
    }
}
