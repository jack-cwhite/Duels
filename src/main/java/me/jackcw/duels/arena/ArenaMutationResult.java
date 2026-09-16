package me.jackcw.duels.arena;

public record ArenaMutationResult(Status status, Arena arena)
{
    public enum Status
    {
        SUCCESS,
        NOT_FOUND,
        IN_USE
    }

    static ArenaMutationResult success(Arena arena)
    {
        return new ArenaMutationResult(Status.SUCCESS, arena);
    }

    static ArenaMutationResult notFound()
    {
        return new ArenaMutationResult(Status.NOT_FOUND, null);
    }

    static ArenaMutationResult inUse()
    {
        return new ArenaMutationResult(Status.IN_USE, null);
    }

    public boolean isSuccess()
    {
        return status == Status.SUCCESS;
    }
}
