package me.jackcw.duels.stats;

import me.jackcw.duels.match.MatchEndReason;
import me.jackcw.duels.match.MatchState;

import java.util.UUID;

/** YAML's durable representation of one match. SQL stores the same shape normalized. */
public final class MatchRecord
{
    private final int id;

    /**
     * Null only for records written before Phase 6 introduced result identity.
     * Left nullable rather than backfilled on load so that a legacy record keeps
     * being the same record across restarts - inventing an id each time the file
     * is read would defeat the point of having one.
     */
    private final UUID resultId;
    private final int arenaId;
    private final UUID player1Id;
    private final String player1Name;
    private final UUID player2Id;
    private final String player2Name;
    private final UUID winnerId;
    private final Integer kitId1;
    private final Integer kitId2;
    private final long startedAt;
    private final Long combatStartedAt;
    private final long endedAt;
    private final MatchEndReason endReason;
    private final MatchState endedState;
    private final String damageCause;

    public MatchRecord(int id, UUID resultId, int arenaId, UUID player1Id, String player1Name, UUID player2Id,
                       String player2Name, UUID winnerId, Integer kitId1, Integer kitId2,
                       long startedAt, Long combatStartedAt, long endedAt, MatchEndReason endReason,
                       MatchState endedState, String damageCause)
    {
        this.id = id;
        this.resultId = resultId;
        this.arenaId = arenaId;
        this.player1Id = player1Id;
        this.player1Name = player1Name;
        this.player2Id = player2Id;
        this.player2Name = player2Name;
        this.winnerId = winnerId;
        this.kitId1 = kitId1;
        this.kitId2 = kitId2;
        this.startedAt = startedAt;
        this.combatStartedAt = combatStartedAt;
        this.endedAt = endedAt;
        this.endReason = endReason;
        this.endedState = endedState;
        this.damageCause = damageCause;
    }

    public int getId() { return id; }
    public UUID getResultId() { return resultId; }
    public int getArenaId() { return arenaId; }
    public UUID getPlayer1Id() { return player1Id; }
    public String getPlayer1Name() { return player1Name; }
    public UUID getPlayer2Id() { return player2Id; }
    public String getPlayer2Name() { return player2Name; }
    public UUID getWinnerId() { return winnerId; }
    public Integer getKitId1() { return kitId1; }
    public Integer getKitId2() { return kitId2; }
    public long getStartedAt() { return startedAt; }
    public Long getCombatStartedAt() { return combatStartedAt; }
    public long getEndedAt() { return endedAt; }
    public MatchEndReason getEndReason() { return endReason; }
    public MatchState getEndedState() { return endedState; }
    public String getDamageCause() { return damageCause; }

    public boolean involves(UUID playerId)
    {
        return playerId.equals(player1Id) || playerId.equals(player2Id);
    }

    public MatchHistoryEntry perspectiveFor(UUID playerId)
    {
        if (playerId.equals(player1Id))
            return new MatchHistoryEntry(id, player1Id, player1Name, player2Id, player2Name,
                    player1Id.equals(winnerId), arenaId, kitId1, kitId2, startedAt, combatStartedAt,
                    endedAt, endReason, endedState, damageCause);

        if (playerId.equals(player2Id))
            return new MatchHistoryEntry(id, player2Id, player2Name, player1Id, player1Name,
                    player2Id.equals(winnerId), arenaId, kitId2, kitId1, startedAt, combatStartedAt,
                    endedAt, endReason, endedState, damageCause);

        return null;
    }
}
