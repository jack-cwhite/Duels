package me.jackcw.duels.arena;

public record ArenaInstanceMutationResult(Status status, ArenaInstance instance)
{
    public enum Status
    {
        SUCCESS,
        NOT_FOUND,
        IN_USE
    }

    static ArenaInstanceMutationResult success(ArenaInstance instance)
    {
        return new ArenaInstanceMutationResult(Status.SUCCESS, instance);
    }

    static ArenaInstanceMutationResult notFound()
    {
        return new ArenaInstanceMutationResult(Status.NOT_FOUND, null);
    }

    static ArenaInstanceMutationResult inUse()
    {
        return new ArenaInstanceMutationResult(Status.IN_USE, null);
    }

    public boolean isSuccess()
    {
        return status == Status.SUCCESS;
    }
}
