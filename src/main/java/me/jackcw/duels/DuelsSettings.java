package me.jackcw.duels;

import me.jackcw.duels.stats.StatsStorageType;
import me.jackcw.jcore.storage.YamlFile;

import java.util.Arrays;
import java.util.logging.Logger;

public final class DuelsSettings
{
    private final StatsStorageType statsStorage;
    private final int challengeExpirySeconds;
    private final int kitSelectionSeconds;
    private final boolean removeOutstandingChallenges;

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

    private static boolean readValidBoolean(YamlFile config, Logger logger, String path, boolean fallback, String rule)
    {
        Object raw = config.getConfig().get(path);

        if (raw instanceof Boolean bool)
            return bool;

        logger.warning("config.yml '" + path + "' " + rule + "; got '" + raw + "', deaulting to " + fallback);
        return fallback;
    }


}
