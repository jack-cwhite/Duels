package me.jackcw.duels.stats;

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

        data.put("arenaId", record.getArenaId());
        data.put("player1Id", record.getPlayer1Id().toString());
        data.put("player2Id", record.getPlayer2Id().toString());
        data.put("winnerId", record.getWinnerId().toString());
        data.put("kitId1", record.getKitId1());
        data.put("kitId2", record.getKitId2());
        data.put("endedAt", record.getEndedAt());

        return data;
    }

    @Override
    public MatchRecord deserialize(int id, Object value)
    {
        if (!(value instanceof Map<?, ?> map))
            throw new IllegalArgumentException(
                    "Expected a map when deserializing a match record"
            );

        return new MatchRecord(
                id,
                (Integer) map.get("arenaId"),
                UUID.fromString((String) map.get("player1Id")),
                UUID.fromString((String) map.get("player2Id")),
                UUID.fromString((String) map.get("winnerId")),
                (Integer) map.get("kitId1"),
                (Integer) map.get("kitId2"),
                ((Number) map.get("endedAt")).longValue()
        );
    }
}
