package me.jackcw.duels.arena;

import me.jackcw.jcore.serialization.SerializerManager;
import me.jackcw.jcore.storage.YamlFile;
import me.jackcw.jcore.storage.YamlRepository;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

import java.util.logging.Logger;

/**
 * One-time upgrade path for arenas saved before spawns and bounds moved off
 * {@link Arena} and onto {@link ArenaInstance}.
 *
 * <p>{@link ArenaSerializer} rejects any field it does not recognise, so an
 * old arena row still carrying an inline {@code spawn1} would fail to load
 * outright once that field is removed from its allowlist. This runs before
 * {@link ArenaManager} (and therefore before {@code ArenaSerializer} ever
 * sees the file), reading the legacy fields directly off the raw YAML,
 * turning each arena that still has them into its first registered instance,
 * then stripping them from the arena's row. An arena with no legacy fields -
 * every arena on a second run, and every arena created after this change -
 * is left untouched, so this is safe to run on every startup.
 */
public final class ArenaInstanceMigrator
{
    private static final String ARENAS_ROOT = "arenas";

    private ArenaInstanceMigrator()
    {
    }

    public static void migrate(YamlFile arenasFile, YamlRepository<ArenaInstance> instanceRepository, SerializerManager serializerManager, Logger logger)
    {
        ConfigurationSection arenas = arenasFile.getConfig().getConfigurationSection(ARENAS_ROOT);

        if (arenas == null)
            return;

        boolean changed = false;

        for (String key : arenas.getKeys(false))
        {
            ConfigurationSection arenaSection = arenas.getConfigurationSection(key);

            if (arenaSection == null || !arenaSection.contains("spawn1"))
                continue;

            int arenaId;

            try
            {
                arenaId = Integer.parseInt(key);
            }
            catch (NumberFormatException e)
            {
                logger.warning("Skipping arena instance migration for non-numeric arena key '" + key + "'");
                continue;
            }

            Location spawn1 = serializerManager.deserialize(arenaSection.get("spawn1"), Location.class);
            Location spawn2 = serializerManager.deserialize(arenaSection.get("spawn2"), Location.class);
            Location boundsCorner1 = serializerManager.deserialize(arenaSection.get("boundsCorner1"), Location.class);
            Location boundsCorner2 = serializerManager.deserialize(arenaSection.get("boundsCorner2"), Location.class);

            int instanceId = instanceRepository.reserveId();
            ArenaInstance instance = new ArenaInstance(instanceId, arenaId);

            instance.setSpawn1(spawn1);
            instance.setSpawn2(spawn2);
            instance.setBoundsCorner1(boundsCorner1);
            instance.setBoundsCorner2(boundsCorner2);

            instanceRepository.save(instance);

            arenaSection.set("spawn1", null);
            arenaSection.set("spawn2", null);
            arenaSection.set("boundsCorner1", null);
            arenaSection.set("boundsCorner2", null);

            changed = true;

            logger.info("Migrated arena #" + arenaId + "'s spawns/bounds to new instance #" + instanceId);
        }

        if (changed)
            arenasFile.save();
    }
}
