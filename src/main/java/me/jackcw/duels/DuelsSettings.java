package me.jackcw.duels;

import me.jackcw.duels.stats.StatsStorageType;
import me.jackcw.duels.arena.DynamicArenaSettings;
import me.jackcw.jcore.storage.YamlFile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public final class DuelsSettings
{
    private StatsStorageType statsStorage;
    private int statsWinRateMinimumMatches;
    private int challengeExpirySeconds;
    private int kitSelectionSeconds;
    private boolean removeOutstandingChallenges;
    private boolean enableGracePeriod;
    private int gracePeriodSeconds;
    private int arenaResetMaxTrackedBlockChanges;
    private int arenaResetBlocksPerTick;
    private String fallbackWorld;
    private DynamicArenaSettings dynamicArenas;

    private String warnedMissingFallbackWorld;

    /** Kept so {@link #fallbackSpawn()} can report a bad world name; every other read validates at load time. */
    private Logger logger;

    public DuelsSettings(YamlFile config, Logger logger)
    {
        reload(config, logger);
    }

    /**
     * Re-reads every value from {@code config} into this same instance, so
     * classes that captured a {@code DuelsSettings} reference at startup
     * (e.g. {@code ChallengeManager}, {@code MatchManager}) see the new
     * values without needing to be reconstructed or re-injected.
     */
    public void reload(YamlFile config, Logger logger)
    {
        this.logger = logger;
        this.statsStorage = readStatsStorage(config, logger);
        this.statsWinRateMinimumMatches = readPositiveInt(
                config, logger, "statistics.win-rate-minimum-matches", 10,
                "must be a whole number of at least 1"
        );

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

        this.fallbackWorld = config.getString("fallback-world", "").trim();
        this.warnedMissingFallbackWorld = null;

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

    public int statsWinRateMinimumMatches()
    {
        return statsWinRateMinimumMatches;
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

    /**
     * The spawn Duels moves a player to when it has to relocate them and has no
     * better place to put them: the configured world's spawn if there is one,
     * otherwise the main world's.
     *
     * <p>Resolved here rather than by each caller so that both cases that need
     * it - a bystander being cleared out of an arena a match is starting in, and
     * a returning player whose captured world no longer exists - agree on the
     * answer. An admin who nominates a hub expects both to land there.
     *
     * <p>Blank by default rather than a world name, because any name shipped as
     * a default would be wrong on most servers, while the main world is the one
     * place every server is guaranteed to have.
     *
     * <p>Callers are still responsible for any policy of their own: this does
     * not know, for instance, that arena bounds might cover the spawn it
     * returns.
     *
     * @return the spawn to use, or {@code null} on a server with no loaded
     *         worlds at all. A live Paper server always has one, so the
     *         relocation callers treat this as non-null; it is the diagnostics
     *         report, which can be asked for at any time, that needs an answer
     *         rather than an exception.
     */
    public Location fallbackSpawn()
    {
        World world = fallbackWorld.isBlank() ? null : Bukkit.getWorld(fallbackWorld);

        if (world != null)
            return world.getSpawnLocation();

        // Warned at most once per configured name: this is read on every
        // relocation, so a typo would otherwise fill the log during a busy
        // match rather than being a single line an admin can find.
        if (!fallbackWorld.isBlank() && !fallbackWorld.equals(warnedMissingFallbackWorld))
        {
            warnedMissingFallbackWorld = fallbackWorld;
            logger.warning("config.yml 'fallback-world' is set to '" + fallbackWorld + "', which is not a loaded"
                    + " world; using the main world's spawn instead. Check the name, or that whatever loads that"
                    + " world has finished starting up.");
        }

        List<World> worlds = Bukkit.getWorlds();

        return worlds.isEmpty() ? null : worlds.getFirst().getSpawnLocation();
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

        logger.warning("config.yml '" + path + "' " + rule + "; got '" + raw + "', defaulting to " + fallback);
        return fallback;
    }


}
