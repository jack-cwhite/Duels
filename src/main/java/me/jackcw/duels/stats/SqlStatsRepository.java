package me.jackcw.duels.stats;

import me.jackcw.duels.Duels;
import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchResult;
import me.jackcw.duels.match.MatchState;
import me.jackcw.jcore.database.Database;
import me.jackcw.jcore.database.DatabaseException;
import me.jackcw.jcore.database.DatabaseResult;
import me.jackcw.jcore.database.DatabaseType;
import me.jackcw.jcore.database.Migration;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        DatabaseType type = plugin.core().databaseConfiguration().getType();

        // Phase 5 intentionally replaces the test-only V1 schema. Existing
        // databases must be removed before starting this build.
        plugin.core().migrations().add(new Migration(1, connection ->
        {
            try (Statement statement = connection.createStatement())
            {
                statement.executeUpdate(matchesTableSql(type));
                statement.executeUpdate(participantsTableSql());
                createIndexIfMissing(connection, statement, "duels_match_participants", "idx_duels_participants_player_kit",
                        "CREATE INDEX idx_duels_participants_player_kit ON duels_match_participants(player_id, kit_id)");
                createIndexIfMissing(connection, statement, "duels_match_participants", "idx_duels_participants_match",
                        "CREATE INDEX idx_duels_participants_match ON duels_match_participants(match_id)");
                createIndexIfMissing(connection, statement, "duels_match_participants", "idx_duels_participants_name",
                        "CREATE INDEX idx_duels_participants_name ON duels_match_participants(player_name)");
                createIndexIfMissing(connection, statement, "duels_matches", "idx_duels_matches_arena",
                        "CREATE INDEX idx_duels_matches_arena ON duels_matches(arena_id)");
                createIndexIfMissing(connection, statement, "duels_matches", "idx_duels_matches_ended",
                        "CREATE INDEX idx_duels_matches_ended ON duels_matches(ended_at)");
            }
        }));
    }

    /** Fails during startup, rather than after the first real result, if the old test schema remains. */
    void validateSchema()
    {
        database().query(
                "SELECT started_at, combat_started_at, end_reason, ended_state, damage_cause FROM duels_matches WHERE 1 = 0",
                ignored -> {});
        database().query(
                "SELECT player_name FROM duels_match_participants WHERE 1 = 0",
                ignored -> {});
    }

    private static String matchesTableSql(DatabaseType type)
    {
        String idColumn = switch (type)
        {
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
            case MYSQL, MARIADB -> "id INT PRIMARY KEY AUTO_INCREMENT";
            case POSTGRESQL -> "id SERIAL PRIMARY KEY";
        };

        return "CREATE TABLE IF NOT EXISTS duels_matches (" + idColumn + ", "
                + "arena_id INTEGER NOT NULL, winner_id VARCHAR(36) NOT NULL, "
                + "started_at BIGINT NOT NULL, combat_started_at BIGINT, ended_at BIGINT NOT NULL, "
                + "end_reason VARCHAR(32) NOT NULL, ended_state VARCHAR(32) NOT NULL, damage_cause VARCHAR(64))";
    }

    private static String participantsTableSql()
    {
        return "CREATE TABLE IF NOT EXISTS duels_match_participants ("
                + "match_id INTEGER NOT NULL, player_id VARCHAR(36) NOT NULL, player_name VARCHAR(16) NOT NULL, "
                + "kit_id INTEGER, won BOOLEAN NOT NULL, FOREIGN KEY (match_id) REFERENCES duels_matches(id))";
    }

    private static void createIndexIfMissing(Connection connection, Statement statement, String tableName,
                                             String indexName, String sql)
            throws SQLException
    {
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, tableName, false, false))
        {
            while (indexes.next())
            {
                String existing = indexes.getString("INDEX_NAME");
                if (existing != null && existing.equalsIgnoreCase(indexName))
                    return;
            }
        }
        catch (SQLException ignored)
        {
            // Some drivers do not expose index metadata consistently. The
            // statement below remains the authoritative operation.
        }

        try
        {
            statement.executeUpdate(sql);
        }
        catch (SQLException exception)
        {
            if (!indexAlreadyExists(exception))
                throw exception;
        }
    }

    private static boolean indexAlreadyExists(SQLException exception)
    {
        String message = exception.getMessage();
        return message != null && message.toLowerCase().contains("already exists");
    }

    @Override
    public CompletableFuture<Void> recordMatch(MatchResult result)
    {
        return plugin.core().tasks().runAsyncFuture(() ->
        {
            try
            {
                database().transaction(connection -> insertMatch(connection, result));
            }
            catch (Exception exception)
            {
                LOGGER.log(Level.SEVERE, "Could not record match result - data lost unless recovered manually: " + result, exception);
                throw new RuntimeException("Could not record match result", exception);
            }
        });
    }

    private void insertMatch(Connection connection, MatchResult result) throws SQLException
    {
        long matchId;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO duels_matches (arena_id, winner_id, started_at, combat_started_at, ended_at, "
                        + "end_reason, ended_state, damage_cause) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS))
        {
            statement.setInt(1, result.arenaId());
            statement.setString(2, result.winnerId().toString());
            statement.setLong(3, result.startedAt());
            setNullableLong(statement, 4, result.combatStartedAt());
            statement.setLong(5, result.endedAt());
            statement.setString(6, result.endReason().name());
            statement.setString(7, result.endedState().name());
            setNullableString(statement, 8, result.damageCause());
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys())
            {
                if (!keys.next())
                    throw new DatabaseException("Could not determine generated match id");
                matchId = keys.getLong(1);
            }
        }

        insertParticipant(connection, matchId, result.player1Id(), result.player1Name(), result.kitId1(),
                result.player1Id().equals(result.winnerId()));
        insertParticipant(connection, matchId, result.player2Id(), result.player2Name(), result.kitId2(),
                result.player2Id().equals(result.winnerId()));
    }

    @Override
    public CompletableFuture<PlayerStats> getPlayerStats(StatsQuery query)
    {
        requirePlayer(query);
        return plugin.core().tasks().submitAsync(() -> StatsAnalytics.summarize(load(query, false, null, null)));
    }

    @Override
    public CompletableFuture<List<MatchHistoryEntry>> getMatchHistory(StatsQuery query, int limit, int offset)
    {
        requirePlayer(query);
        return plugin.core().tasks().submitAsync(() -> load(query, true, limit, offset));
    }

    @Override
    public CompletableFuture<List<LeaderboardEntry>> getLeaderboard(StatsQuery query, LeaderboardMetric metric,
                                                                     int minimumMatches, int limit)
    {
        return plugin.core().tasks().submitAsync(() ->
        {
            Map<UUID, List<MatchHistoryEntry>> grouped = new LinkedHashMap<>();
            for (MatchHistoryEntry entry : load(query, false, null, null))
                grouped.computeIfAbsent(entry.playerId(), ignored -> new ArrayList<>()).add(entry);

            return grouped.values().stream()
                    .map(history -> new LeaderboardEntry(history.getFirst().playerId(), latestName(history),
                            StatsAnalytics.summarize(history)))
                    .filter(entry -> entry.value(metric) > 0)
                    .filter(entry -> metric != LeaderboardMetric.WIN_RATE || entry.stats().matches() >= minimumMatches)
                    .sorted(YamlStatsRepository.leaderboardComparator(metric))
                    .limit(limit)
                    .toList();
        });
    }

    @Override
    public CompletableFuture<Optional<StatsPlayer>> findPlayer(String name)
    {
        return plugin.core().tasks().submitAsync(() ->
        {
            StatsPlayer[] found = new StatsPlayer[1];
            database().query(
                    "SELECT player_id, player_name FROM duels_match_participants "
                            + "WHERE LOWER(player_name) = LOWER(?) ORDER BY match_id DESC LIMIT 1",
                    result ->
                    {
                        if (result.next())
                            found[0] = new StatsPlayer(UUID.fromString(result.getString("player_id")),
                                    result.getString("player_name"));
                    }, name);
            return Optional.ofNullable(found[0]);
        });
    }

    private List<MatchHistoryEntry> load(StatsQuery query, boolean newestFirst, Integer limit, Integer offset)
    {
        StringBuilder sql = new StringBuilder(
                "SELECT m.id AS match_id, m.arena_id, m.started_at, m.combat_started_at, m.ended_at, "
                        + "m.end_reason, m.ended_state, m.damage_cause, "
                        + "pa.player_id AS viewed_id, pa.player_name AS viewed_name, pa.kit_id AS viewed_kit, pa.won AS viewed_won, "
                        + "pb.player_id AS opponent_id, pb.player_name AS opponent_name, pb.kit_id AS opponent_kit "
                        + "FROM duels_match_participants pa "
                        + "JOIN duels_match_participants pb ON pa.match_id = pb.match_id AND pa.player_id <> pb.player_id "
                        + "JOIN duels_matches m ON m.id = pa.match_id WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        appendFilters(sql, parameters, query);
        sql.append(" ORDER BY m.ended_at ").append(newestFirst ? "DESC" : "ASC")
                .append(", m.id ").append(newestFirst ? "DESC" : "ASC");
        if (limit != null)
        {
            sql.append(" LIMIT ? OFFSET ?");
            parameters.add(limit);
            parameters.add(offset == null ? 0 : offset);
        }

        List<MatchHistoryEntry> entries = new ArrayList<>();
        database().query(sql.toString(), result ->
        {
            while (result.next())
                entries.add(map(result));
        }, parameters.toArray());
        return entries;
    }

    private static void appendFilters(StringBuilder sql, List<Object> parameters, StatsQuery query)
    {
        append(sql, parameters, "pa.player_id = ?", query.playerId() == null ? null : query.playerId().toString());
        append(sql, parameters, "pb.player_id = ?", query.opponentId() == null ? null : query.opponentId().toString());
        append(sql, parameters, "pa.kit_id = ?", query.playerKitId());
        append(sql, parameters, "pb.kit_id = ?", query.opponentKitId());
        append(sql, parameters, "m.arena_id = ?", query.arenaId());
        append(sql, parameters, "m.ended_at >= ?", query.endedAfter());
        append(sql, parameters, "m.ended_at < ?", query.endedBefore());
    }

    private static void append(StringBuilder sql, List<Object> parameters, String condition, Object value)
    {
        if (value == null)
            return;
        sql.append(" AND ").append(condition);
        parameters.add(value);
    }

    private static MatchHistoryEntry map(DatabaseResult result)
    {
        return new MatchHistoryEntry(
                result.getLong("match_id"),
                UUID.fromString(result.getString("viewed_id")),
                result.getString("viewed_name"),
                UUID.fromString(result.getString("opponent_id")),
                result.getString("opponent_name"),
                result.getBoolean("viewed_won"),
                result.getInt("arena_id"),
                nullableInt(result, "viewed_kit"),
                nullableInt(result, "opponent_kit"),
                result.getLong("started_at"),
                nullableLong(result, "combat_started_at"),
                result.getLong("ended_at"),
                MatchEndReason.valueOf(result.getString("end_reason")),
                MatchState.valueOf(result.getString("ended_state")),
                result.getString("damage_cause"));
    }

    private static Integer nullableInt(DatabaseResult result, String column)
    {
        return result.isNull(column) ? null : result.getInt(column);
    }

    private static Long nullableLong(DatabaseResult result, String column)
    {
        return result.isNull(column) ? null : result.getLong(column);
    }

    private static String latestName(List<MatchHistoryEntry> history)
    {
        return history.stream().max(Comparator.comparingLong(MatchHistoryEntry::endedAt))
                .map(MatchHistoryEntry::playerName).orElse("Unknown");
    }

    private static void insertParticipant(Connection connection, long matchId, UUID playerId, String playerName,
                                          Integer kitId, boolean won) throws SQLException
    {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO duels_match_participants (match_id, player_id, player_name, kit_id, won) VALUES (?, ?, ?, ?, ?)"))
        {
            statement.setLong(1, matchId);
            statement.setString(2, playerId.toString());
            statement.setString(3, playerName);
            if (kitId == null)
                statement.setNull(4, Types.INTEGER);
            else
                statement.setInt(4, kitId);
            statement.setBoolean(5, won);
            statement.executeUpdate();
        }
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException
    {
        if (value == null)
            statement.setNull(index, Types.BIGINT);
        else
            statement.setLong(index, value);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value) throws SQLException
    {
        if (value == null)
            statement.setNull(index, Types.VARCHAR);
        else
            statement.setString(index, value);
    }

    private static void requirePlayer(StatsQuery query)
    {
        if (query.playerId() == null)
            throw new IllegalArgumentException("A player is required for profile statistics");
    }

    private Database database()
    {
        return plugin.core().database();
    }
}
