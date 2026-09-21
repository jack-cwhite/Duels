package me.jackcw.duels;

import me.jackcw.duels.stats.StatsStorageType;
import me.jackcw.duels.arena.DynamicArenaSettings;
import me.jackcw.jcore.storage.YamlFile;

import java.util.Arrays;
import java.util.logging.Logger;

public final class DuelsSettings
{
    private final StatsStorageType statsStorage;
    private final int challengeExpirySeconds;
    private final int kitSelectionSeconds;
    private final boolean removeOutstandingChallenges;
    private final boolean enableGracePeriod;
    private final int gracePeriodSeconds;
    private final int arenaResetMaxTrackedBlockChanges;
    private final int arenaResetBlocksPerTick;
    private final DynamicArenaSettings dynamicArenas;

    public DuelsSettings(YamlFile config, Logger logger)
    {
        this.statsStorage = readStatsStorage(config, logger);

        this.challengeExpirySeconds = readNonNegativeInt(
                config, logger, "duel-request-expiry-time", 30,
                "must be a whole number of 0 or greater"
        );

        this.kitSelectionSeconds = readPositiveInt(
                config, logger, "kit-selection-time", 15,
                "must be a whole number of at least 1"
        );

        this.removeOutstandingChallenges = readValidBoolean(
                config, logger, "remove-outstanding-challenge-requests", true,
                "must be a valid boolean: true or false"
        );

        this.enableGracePeriod = readValidBoolean(
                config, logger, "enable-grace-period", true,
                "must be a valid boolean: true or false"
        );

        this.gracePeriodSeconds = readPositiveInt(
                config, logger, "grace-period-seconds", 5,
                "must be a valid number of at least 1 "
        );

        this.arenaResetMaxTrackedBlockChanges = readPositiveInt(
                config, logger, "arena-reset-max-tracked-block-changes", 4000,
                "must be a whole number of at least 1"
        );

        this.arenaResetBlocksPerTick = readPositiveInt(
                config, logger, "arena-reset-blocks-per-tick", 64,
                "must be a whole number of at least 1"
        );

        this.dynamicArenas = new DynamicArenaSettings(
                readNonBlankString(config, logger, "dynamic-arenas.world-name", "duels_dynamic_arenas"),
                readPositiveInt(config, logger, "dynamic-arenas.max-slots", 64, "must be a whole number of at least 1"),
                readPositiveInt(config, logger, "dynamic-arenas.slots-per-row", 8, "must be a whole number of at least 1"),
                readPositiveInt(config, logger, "dynamic-arenas.slot-width", 256, "must be a whole number of at least 1"),
                readPositiveInt(config, logger, "dynamic-arenas.slot-length", 256, "must be a whole number of at least 1"),
                readNonNegativeInt(config, logger, "dynamic-arenas.slot-padding", 16, "must be a whole number of 0 or greater"),
                config.getInt("dynamic-arenas.base-y", 64),
                readPositiveLong(config, logger, "dynamic-arenas.max-template-volume", 2_000_000L, "must be a whole number of at least 1"),
                readPositiveInt(config, logger, "dynamic-arenas.provision-timeout-seconds", 30, "must be a whole number of at least 1"),
                readPositiveInt(config, logger, "dynamic-arenas.cleanup-blocks-per-tick", 1024, "must be a whole number of at least 1")
        );
    }

    public StatsStorageType statsStorage()
    {
        return statsStorage;
    }

    public int challengeExpirySeconds()
    {
        return challengeExpirySeconds;
    }

    public int kitSelectionSeconds()
    {
        return kitSelectionSeconds;
    }

    public boolean removeOutstandingChallenges()
    {
        return removeOutstandingChallenges;
    }

    public boolean enableGracePeriod()
    {
        return enableGracePeriod;
    }

    public int gracePeriodSeconds()
    {
        return gracePeriodSeconds;
    }

    public int arenaResetMaxTrackedBlockChanges()
    {
        return arenaResetMaxTrackedBlockChanges;
    }

    public int arenaResetBlocksPerTick()
    {
        return arenaResetBlocksPerTick;
    }

    public long dynamicArenaMaxTemplateVolume()
    {
        return dynamicArenas.maxTemplateVolume();
    }

    public DynamicArenaSettings dynamicArenas()
    {
        return dynamicArenas;
    }

    private static StatsStorageType readStatsStorage(YamlFile config, Logger logger)
    {
        Object value = config.getConfig().get("stats-storage");
        String raw = value instanceof String string ? string : "";

        try
        {
            return StatsStorageType.valueOf(raw.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            logger.warning(
                    "config.yml has an invalid 'stats-storage': '" + value + "' (expected one of "
                            + Arrays.toString(StatsStorageType.values()) + "); defaulting to SQL"
            );

            return StatsStorageType.SQL;
        }
    }

    private static int readNonNegativeInt(YamlFile config, Logger logger, String path, int fallback, String rule)
    {
        Object raw = config.getConfig().get(path);

        if (raw instanceof Number number && number.intValue() >= 0)
            return number.intValue();

        logger.warning("config.yml '" + path + "' " + rule + "; got '" + raw + "', defaulting to " + fallback);
        return fallback;
    }

    private static int readPositiveInt(YamlFile config, Logger logger, String path, int fallback, String rule)
    {
        Object raw = config.getConfig().get(path);

        if (raw instanceof Number number && number.intValue() > 0)
            return number.intValue();

        logger.warning("config.yml '" + path + "' " + rule + "; got '" + raw + "', defaulting to " + fallback);
        return fallback;
    }

    private static long readPositiveLong(YamlFile config, Logger logger, String path, long fallback, String rule)
    {
        Object raw = config.getConfig().get(path);

        if (raw instanceof Number number && number.longValue() > 0)
            return number.longValue();

        logger.warning("config.yml '" + path + "' " + rule + "; got '" + raw + "', defaulting to " + fallback);
        return fallback;
    }

    private static String readNonBlankString(YamlFile config, Logger logger, String path, String fallback)
    {
        Object raw = config.getConfig().get(path);
        if (raw instanceof String string && !string.isBlank())
            return string;

        logger.warning("config.yml '" + path + "' must be a non-empty string; got '" + raw + "', defaulting to " + fallback);
        return fallback;
    }

    private static boolean readValidBoolean(YamlFile config, Logger logger, String path, boolean fallback, String rule)
    {
        Object raw = config.getConfig().get(path);

        if (raw instanceof Boolean bool)
            return bool;

        logger.warning("config.yml '" + path + "' " + rule + "; got '" + raw + "', deaulting to " + fallback);
        return fallback;
    }


}
