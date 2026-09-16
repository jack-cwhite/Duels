package me.jackcw.duels.match;

import me.jackcw.duels.arena.Arena;
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
    private final Arena arena;
    private final Location player1Location;
    private final Location player2Location;
    private final List<Kit> availableKits;
    private final StateMachine<MatchState> state;
    private final Map<UUID, Kit> selectedKits = new HashMap<>();
    private final Map<UUID, Integer> appliedKits = new HashMap<>();
    private Countdown countdown;

    public Match(UUID player1Id, UUID player2Id, Arena arena, Location player1Location, Location player2Location, List<Kit> availableKits)
    {
        this.player1Id = player1Id;
        this.player2Id = player2Id;
        this.arena = arena;
        this.player1Location = player1Location;
        this.player2Location = player2Location;
        this.availableKits = availableKits.stream().map(Kit::copy).toList();
        this.state = buildStateMachine();
    }

    private static StateMachine<MatchState> buildStateMachine()
    {
        return StateMachine.create(MatchState.STARTING)
                .allowTransition(MatchState.STARTING, MatchState.KIT_SELECTION)
                .allowTransition(MatchState.STARTING, MatchState.COUNTDOWN)
                .allowTransition(MatchState.STARTING, MatchState.ENDED)
                .allowTransition(MatchState.KIT_SELECTION, MatchState.IN_PROGRESS)
                .allowTransition(MatchState.KIT_SELECTION, MatchState.ENDED)
                .allowTransition(MatchState.COUNTDOWN, MatchState.IN_PROGRESS)
                .allowTransition(MatchState.COUNTDOWN, MatchState.ENDED)
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

    public Arena getArena()
    {
        return arena;
    }

    public List<Kit> getAvailableKits()
    {
        return availableKits;
    }

    public MatchState getState()
    {
        return state.getState();
    }

    public void setState(MatchState newState)
    {
        state.transition(newState);
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
