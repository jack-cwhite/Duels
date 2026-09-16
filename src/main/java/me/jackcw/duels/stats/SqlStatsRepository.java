package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.jcore.database.Database;
import me.jackcw.jcore.database.DatabaseException;
import me.jackcw.jcore.database.DatabaseType;
import me.jackcw.jcore.database.Migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SqlStatsRepository implements StatsRepository
{
    private static final Logger LOGGER = Logger.getLogger(SqlStatsRepository.class.getName());

    private final Duels plugin;

    public SqlStatsRepository(Duels plugin)
    {
        this.plugin = plugin;

        DatabaseType type = plugin.getJCore().databaseConfiguration().getType();

        plugin.getJCore().migrations().add(new Migration(1, connection ->
        {
            try (Statement statement = connection.createStatement())
            {
                statement.executeUpdate(matchesTableSql(type));
                statement.executeUpdate(participantsTableSql());

                createIndexIfMissing(connection, statement, "idx_duels_participants_player",
                        "CREATE INDEX idx_duels_participants_player ON duels_match_participants(player_id)");
                createIndexIfMissing(connection, statement, "idx_duels_participants_match",
                        "CREATE INDEX idx_duels_participants_match ON duels_match_participants(match_id)");
            }
        }));
    }

    private static String matchesTableSql(DatabaseType type)
    {
        String idColumn = switch (type)
        {
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL, MARIADB -> "id INT PRIMARY KEY AUTO_INCREMENT";
            case POSTGRESQL -> "id SERIAL PRIMARY KEY";
        };

        return "CREATE TABLE IF NOT EXISTS duels_matches (" +
                idColumn + ", " +
                "arena_id INTEGER NOT NULL, " +
                "winner_id VARCHAR(36) NOT NULL, " +
                "ended_at BIGINT NOT NULL" +
                ")";
    }

    private static String participantsTableSql()
    {
        return "CREATE TABLE IF NOT EXISTS duels_match_participants (" +
                "match_id INTEGER NOT NULL, " +
                "player_id VARCHAR(36) NOT NULL, " +
                "kit_id INTEGER, " +
                "won BOOLEAN NOT NULL, " +
                "FOREIGN KEY (match_id) REFERENCES duels_matches(id)" +
                ")";
    }

    private static void createIndexIfMissing(Connection connection, Statement statement, String indexName, String sql)
            throws SQLException
    {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, "duels_match_participants", false, false))
        {
            while (indexes.next())
            {
                String existing = indexes.getString("INDEX_NAME");

                if (existing != null && existing.equalsIgnoreCase(indexName))
                    return;
            }
        }

        statement.executeUpdate(sql);
    }

    @Override
    public void recordMatch(int arenaId, UUID player1Id, UUID player2Id, UUID winnerId, Integer kitId1, Integer kitId2, long endedAt)
    {
        plugin.getJCore().tasks().runAsync(() ->
        {
            try
            {
                database().transaction(connection ->
                {
                    long matchId;

                    try (PreparedStatement insertMatch = connection.prepareStatement(
                            "INSERT INTO duels_matches (arena_id, winner_id, ended_at) VALUES (?, ?, ?)",
                            Statement.RETURN_GENERATED_KEYS))
                    {
                        insertMatch.setInt(1, arenaId);
                        insertMatch.setString(2, winnerId.toString());
                        insertMatch.setLong(3, endedAt);
                        insertMatch.executeUpdate();

                        try (ResultSet keys = insertMatch.getGeneratedKeys())
                        {
                            if (!keys.next())
                                throw new DatabaseException("Could not determine generated match id");

                            matchId = keys.getLong(1);
                        }
                    }

                    insertParticipant(connection, matchId, player1Id, kitId1, player1Id.equals(winnerId));
                    insertParticipant(connection, matchId, player2Id, kitId2, player2Id.equals(winnerId));
                });
            }
            catch (Exception e)
            {
                LOGGER.log(Level.WARNING, "Could not record match result", e);
            }
        });
    }

    @Override
    public CompletableFuture<Integer> getWins(UUID playerId)
    {
        return countWhereAsync("player_id = ? AND won = ?", playerId.toString(), true);
    }

    @Override
    public CompletableFuture<Integer> getLosses(UUID playerId)
    {
        return countWhereAsync("player_id = ? AND won = ?", playerId.toString(), false);
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> getTopPlayers(int limit)
    {
        return plugin.getJCore().tasks().submitAsync(() ->
        {
            List<LeaderboardEntry> entries = new ArrayList<>();

            database().query(
                    "SELECT player_id, COUNT(*) AS wins FROM duels_match_participants " +
                            "WHERE won = ? GROUP BY player_id ORDER BY wins DESC LIMIT ?",
                    result ->
                    {
                        while (result.next())
                            entries.add(new LeaderboardEntry(UUID.fromString(result.getString("player_id")), result.getInt("wins")));
                    },
                    true, limit
            );

            return entries;
        });
    }

    @Override
    public CompletableFuture<HeadToHead> getHeadToHead(UUID playerA, UUID playerB, Integer kitIdA, Integer kitIdB, Integer arenaId)
    {
        return plugin.getJCore().tasks().submitAsync(() ->
        {
            StringBuilder sql = new StringBuilder(
                    "SELECT pa.won AS a_won FROM duels_match_participants pa " +
                            "JOIN duels_match_participants pb ON pa.match_id = pb.match_id " +
                            "JOIN duels_matches m ON m.id = pa.match_id " +
                            "WHERE pa.player_id = ? AND pb.player_id = ?"
            );

            List<Object> parameters = new ArrayList<>(List.of(playerA.toString(), playerB.toString()));

            if (kitIdA != null)
            {
                sql.append(" AND pa.kit_id = ?");
                parameters.add(kitIdA);
            }

            if (kitIdB != null)
            {
                sql.append(" AND pb.kit_id = ?");
                parameters.add(kitIdB);
            }

            if (arenaId != null)
            {
                sql.append(" AND m.arena_id = ?");
                parameters.add(arenaId);
            }

            int[] wins = new int[2];

            database().query(sql.toString(), result ->
            {
                while (result.next())
                {
                    if (result.getBoolean("a_won"))
                        wins[0]++;
                    else
                        wins[1]++;
                }
            }, parameters.toArray());

            return new HeadToHead(playerA, playerB, wins[0], wins[1]);
        });
    }

    private CompletableFuture<Integer> countWhereAsync(String condition, Object... parameters)
    {
        return plugin.getJCore().tasks().submitAsync(() ->
        {
            int[] total = new int[1];

            database().query(
                    "SELECT COUNT(*) AS total FROM duels_match_participants WHERE " + condition,
                    result ->
                    {
                        result.next();
                        total[0] = result.getInt("total");
                    },
                    parameters
            );

            return total[0];
        });
    }

    private void insertParticipant(Connection connection, long matchId, UUID playerId, Integer kitId, boolean won) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO duels_match_participants (match_id, player_id, kit_id, won) VALUES (?, ?, ?, ?)"))
        {
            statement.setLong(1, matchId);
            statement.setString(2, playerId.toString());

            if (kitId != null)
                statement.setInt(3, kitId);
            else
                statement.setNull(3, Types.INTEGER);

            statement.setBoolean(4, won);
            statement.executeUpdate();
        }
    }

    private Database database()
    {
        return plugin.getJCore().database();
    }
}
