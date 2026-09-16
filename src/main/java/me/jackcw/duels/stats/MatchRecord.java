package me.jackcw.duels.stats;

import java.util.UUID;

public final class MatchRecord
{
    private final int id;
    private final int arenaId;
    private final UUID player1Id;
    private final UUID player2Id;
    private final UUID winnerId;
    private final Integer kitId1;
    private final Integer kitId2;
    private final long endedAt;

    public MatchRecord(int id, int arenaId, UUID player1Id, UUID player2Id, UUID winnerId, Integer kitId1, Integer kitId2, long endedAt)
    {
        this.id = id;
        this.arenaId = arenaId;
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.winnerId = winnerId;
        this.kitId1 = kitId1;
        this.kitId2 = kitId2;
        this.endedAt = endedAt;
    }

    public int getId()
    {
        return id;
    }

    public int getArenaId()
    {
        return arenaId;
    }

    public UUID getPlayer1Id()
    {
        return player1Id;
    }

    public UUID getPlayer2Id()
    {
        return player2Id;
    }

    public UUID getWinnerId()
    {
        return winnerId;
    }

    public Integer getKitId1()
    {
        return kitId1;
    }

    public Integer getKitId2()
    {
        return kitId2;
    }

    public long getEndedAt()
    {
        return endedAt;
    }

    public boolean involves(UUID playerId)
    {
        return playerId.equals(player1Id) || playerId.equals(player2Id);
    }

    public boolean won(UUID playerId)
    {
        return playerId.equals(winnerId);
    }

    public Integer kitIdFor(UUID playerId)
    {
        if (playerId.equals(player1Id))
            return kitId1;

        if (playerId.equals(player2Id))
            return kitId2;

        return null;
    }
}
