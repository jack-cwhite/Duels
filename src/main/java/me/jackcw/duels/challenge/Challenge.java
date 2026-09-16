package me.jackcw.duels.challenge;

import java.time.Instant;
import java.util.UUID;

public final class Challenge
{
    private final UUID challenger;
    private final UUID challenged;
    private final Instant expiry;

    public Challenge(UUID challenger, UUID challenged, Instant expiry)
    {
        this.challenger = challenger;
        this.challenged = challenged;
        this.expiry = expiry;
    }

    public UUID getChallenger()
    {
        return challenger;
    }

    public UUID getChallenged()
    {
        return challenged;
    }

    public Instant getExpiry()
    {
        return expiry;
    }
}
