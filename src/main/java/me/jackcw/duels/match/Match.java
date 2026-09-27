package me.jackcw.duels.match;

import me.jackcw.duels.arena.Arena;
import me.jackcw.duels.arena.ArenaInstance;
import me.jackcw.duels.kit.Kit;
import me.jackcw.jcore.countdown.Countdown;
import me.jackcw.jcore.state.StateMachine;
import org.bukkit.Location;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Match
{
    private final UUID player1Id;
    private final UUID player2Id;
    private final String player1Name;
    private final String player2Name;
    private final ArenaInstance arenaInstance;
    private final Location player1Location;
    private final Location player2Location;
    private final List<Kit> availableKits;
    private final StateMachine<MatchState> state;
    private final long startedAt;
    private Long combatStartedAt;
    private final Map<UUID, Kit> selectedKits = new HashMap<>();
    private final Map<UUID, Integer> appliedKits = new HashMap<>();
    private Countdown countdown;

    public Match(UUID player1Id, String player1Name, UUID player2Id, String player2Name,
                 ArenaInstance arenaInstance, Location player1Location, Location player2Location, List<Kit> availableKits)
    {
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.player1Name = player1Name;
        this.player2Name = player2Name;
        this.arenaInstance = arenaInstance;
        this.player1Location = player1Location;
        this.player2Location = player2Location;
        this.availableKits = availableKits.stream().map(Kit::copy).toList();
        this.state = buildStateMachine();
        this.startedAt = System.currentTimeMillis();
    }

    private static StateMachine<MatchState> buildStateMachine()
    {
        return StateMachine.create(MatchState.PREGAME)
                .allowTransition(MatchState.PREGAME, MatchState.GRACE)
                .allowTransition(MatchState.PREGAME, MatchState.IN_PROGRESS)
                .allowTransition(MatchState.PREGAME, MatchState.ENDED)
                .allowTransition(MatchState.GRACE, MatchState.IN_PROGRESS)
                .allowTransition(MatchState.GRACE, MatchState.ENDED)
                .allowTransition(MatchState.IN_PROGRESS, MatchState.ENDED);
    }

    public UUID getOpponent(UUID uuid)
    {
        if (uuid.equals(player1Id))
            return player2Id;

        if (uuid.equals(player2Id))
            return player1Id;

        return null;
    }

    public Location getLocation(UUID uuid)
    {
        if (uuid.equals(player1Id))
            return player1Location;

        if (uuid.equals(player2Id))
            return player2Location;

        return null;
    }

    public UUID getPlayer1Id()
    {
        return player1Id;
    }

    public UUID getPlayer2Id()
    {
        return player2Id;
    }

    public String getPlayer1Name()
    {
        return player1Name;
    }

    public String getPlayer2Name()
    {
        return player2Name;
    }

    public ArenaInstance getArenaInstance()
    {
        return arenaInstance;
    }

    // Wall-clock rather than a tick count so live displays and persisted session
    // duration remain meaningful across lag spikes.
    public long getStartedAt()
    {
        return startedAt;
    }

    public Long getCombatStartedAt()
    {
        return combatStartedAt;
    }

    /** Records the exact transition at which damage becomes meaningful. */
    public void beginCombat()
    {
        setState(MatchState.IN_PROGRESS);
    }

    public List<Kit> getAvailableKits()
    {
        return availableKits;
    }

    public MatchState getState()
    {
        return state.getState();
    }

    /**
     * Whether this match is occupying its arena right now.
     *
     * <p>Both duellists are teleported to their spawns as soon as the match is
     * created, while it is still {@link MatchState#PREGAME} and they are
     * choosing kits, so "in the arena" starts well before combat does. Rules
     * about what a duel may do to the world - containment and rollback - key on
     * this rather than on {@link MatchState#IN_PROGRESS}, because a bucket of
     * lava emptied at a spawn during kit selection is in the arena just as much
     * as one emptied mid-fight, and previously was neither prevented nor
     * restored.
     *
     * <p>Rules about how a duellist may be <em>treated</em> still key on
     * {@code IN_PROGRESS} instead, since those depend on the fight actually
     * being live.
     */
    public boolean isLive()
    {
        return getState() != MatchState.ENDED;
    }

    public void setState(MatchState newState)
    {
        state.transition(newState);
        if (newState == MatchState.IN_PROGRESS && combatStartedAt == null)
            combatStartedAt = System.currentTimeMillis();
    }

    public void setCountdown(Countdown countdown)
    {
        this.countdown = countdown;
    }

    public Countdown getCountdown()
    {
        return countdown;
    }

    public void recordSelectedKit(UUID playerId, Kit kit)
    {
        selectedKits.put(playerId, kit);
    }

    public Kit getSelectedKit(UUID playerId)
    {
        return selectedKits.get(playerId);
    }

    public void recordAppliedKit(UUID playerId, int kitId)
    {
        appliedKits.put(playerId, kitId);
    }

    public Integer getAppliedKit(UUID playerId)
    {
        return appliedKits.get(playerId);
    }
}
