package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchState;
import me.jackcw.jcore.serialization.RepositorySerializer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class MatchRecordSerializer implements RepositorySerializer<MatchRecord>
{
    @Override
    public Object serialize(MatchRecord record)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("resultId", record.getResultId() == null ? null : record.getResultId().toString());
        data.put("arenaId", record.getArenaId());
        data.put("player1Id", record.getPlayer1Id().toString());
        data.put("player1Name", record.getPlayer1Name());
        data.put("player2Id", record.getPlayer2Id().toString());
        data.put("player2Name", record.getPlayer2Name());
        data.put("winnerId", record.getWinnerId().toString());
        data.put("kitId1", record.getKitId1());
        data.put("kitId2", record.getKitId2());
        data.put("startedAt", record.getStartedAt());
        data.put("combatStartedAt", record.getCombatStartedAt());
        data.put("endedAt", record.getEndedAt());
        data.put("endReason", record.getEndReason().name());
        data.put("endedState", record.getEndedState().name());
        data.put("damageCause", record.getDamageCause());
        return data;
    }

    @Override
    public MatchRecord deserialize(int id, Object value)
    {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException("Expected a map when deserializing a match record");

        return new MatchRecord(
                id,
                uuidOrNull(map.get("resultId")),
                ((Number) map.get("arenaId")).intValue(),
                UUID.fromString((String) map.get("player1Id")),
                (String) map.get("player1Name"),
                UUID.fromString((String) map.get("player2Id")),
                (String) map.get("player2Name"),
                UUID.fromString((String) map.get("winnerId")),
                numberOrNull(map.get("kitId1")),
                numberOrNull(map.get("kitId2")),
                ((Number) map.get("startedAt")).longValue(),
                longOrNull(map.get("combatStartedAt")),
                ((Number) map.get("endedAt")).longValue(),
                MatchEndReason.valueOf((String) map.get("endReason")),
                MatchState.valueOf((String) map.get("endedState")),
                (String) map.get("damageCause")
        );
    }

    /** Absent on any record written before Phase 6 gave results an identity. */
    private static UUID uuidOrNull(Object value)
    {
        return value instanceof String text && !text.isBlank() ? UUID.fromString(text) : null;
    }

    private static Integer numberOrNull(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Long longOrNull(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }
}
