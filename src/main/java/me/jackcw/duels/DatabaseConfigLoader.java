package me.jackcw.duels;

import me.jackcw.jcore.database.DatabaseConfiguration;
import me.jackcw.jcore.database.DatabaseType;
import me.jackcw.jcore.database.MySQLConfiguration;
import me.jackcw.jcore.database.PostgreSQLConfiguration;
import me.jackcw.jcore.database.SQLiteConfiguration;
import me.jackcw.jcore.storage.YamlDefaultsMerger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.logging.Logger;

final class DatabaseConfigLoader
{
    private static final String FILE_NAME = "database.yml";
    private static final Logger LOGGER = Logger.getLogger(DatabaseConfigLoader.class.getName());

    private DatabaseConfigLoader()
    {
    }

    static DatabaseConfiguration load(JavaPlugin plugin)
    {
        File file = new File(plugin.getDataFolder(), FILE_NAME);

        if (!file.exists())
            plugin.saveResource(FILE_NAME, false);
        else
            mergeDefaults(plugin, file);

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        DatabaseType type = parseType(config.getString("type"));

        return switch (type)
        {
            case SQLITE -> new DatabaseConfiguration(DatabaseType.SQLITE, sqlite(config));
            case MYSQL -> new DatabaseConfiguration(DatabaseType.MYSQL, mysql(config, "mysql"));
            case MARIADB -> new DatabaseConfiguration(DatabaseType.MARIADB, mysql(config, "mariadb"));
            case POSTGRESQL -> new DatabaseConfiguration(DatabaseType.POSTGRESQL, postgresql(config));
        };
    }

    private static void mergeDefaults(JavaPlugin plugin, File file)
    {
        try (InputStream defaults = plugin.getResource(FILE_NAME))
        {
            if (defaults != null)
                new YamlDefaultsMerger().merge(file, defaults);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("Could not update " + FILE_NAME + " defaults", e);
        }
    }

    private static DatabaseType parseType(String raw)
    {
        if (raw == null)
        {
            LOGGER.warning("database.yml is missing 'type'; defaulting to SQLITE");
            return DatabaseType.SQLITE;
        }

        try
        {
            return DatabaseType.valueOf(raw.trim().toUpperCase());
        }
        catch (IllegalArgumentException e)
        {
            LOGGER.warning(
                    "database.yml has an invalid 'type': '" + raw + "' (expected one of "
                            + Arrays.toString(DatabaseType.values()) + "); defaulting to SQLITE"
            );

            return DatabaseType.SQLITE;
        }
    }

    private static SQLiteConfiguration sqlite(FileConfiguration config)
    {
        return new SQLiteConfiguration(config.getString("sqlite.file", "database.db"));
    }

    private static MySQLConfiguration mysql(FileConfiguration config, String section)
    {
        return new MySQLConfiguration(
                config.getString(section + ".host", "localhost"),
                config.getInt(section + ".port", 3306),
                config.getString(section + ".database", "duels"),
                config.getString(section + ".username", "root"),
                config.getString(section + ".password", "")
        );
    }

    private static PostgreSQLConfiguration postgresql(FileConfiguration config)
    {
        return new PostgreSQLConfiguration(
                config.getString("postgresql.host", "localhost"),
                config.getInt("postgresql.port", 5432),
                config.getString("postgresql.database", "duels"),
                config.getString("postgresql.username", "postgres"),
                config.getString("postgresql.password", "")
        );
    }
}
