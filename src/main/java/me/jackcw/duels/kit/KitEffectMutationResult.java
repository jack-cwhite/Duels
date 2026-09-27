package me.jackcw.duels.kit;

public record KitEffectMutationResult(Status status, KitEffect effect)
{
    public enum Status
    {
        SUCCESS,
        KIT_NOT_FOUND,
        EFFECT_NOT_FOUND,
        ALREADY_PRESENT,
        INVALID_TYPE,
        INSTANT_TYPE,
        INVALID_LEVEL
    }

    public boolean isSuccess()
    {
        return status == Status.SUCCESS;
    }
}
