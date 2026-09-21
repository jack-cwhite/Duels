package me.jackcw.duels.match;

/** Outcome of preparing and committing a match start. */
public record MatchStartResult(Status status, Match match)
{
    public enum Status { SUCCESS, INVALID_PLAYERS, PLAYERS_BUSY, ARENA_UNAVAILABLE }
    public static MatchStartResult success(Match match) { return new MatchStartResult(Status.SUCCESS, match); }
    public static MatchStartResult failure(Status status) { return new MatchStartResult(status, null); }
}
